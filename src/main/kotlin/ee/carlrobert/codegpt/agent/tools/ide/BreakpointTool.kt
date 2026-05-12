package ee.carlrobert.codegpt.agent.tools.ide

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.JSONSerializer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import ee.carlrobert.codegpt.agent.tools.BaseTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tool that creates a debugger breakpoint on behalf of the agent/user.
 */
class BreakpointTool(
    private val project: Project,
    sessionId: String,
    hookManager: HookManager,
) : BaseTool<BreakpointTool.Args, BreakpointTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    name = NAME,
    description = """
        Manage a line breakpoint in the current project.
        Default action is create. Supported actions: create, edit, delete.
        Use file_path and a 1-based line number to identify the location.
        breakpoint_type selects a specific IntelliJ line breakpoint type when needed.
        condition sets or updates the breakpoint condition for create and edit.
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId,
) {

    companion object {
        const val NAME = "create_breakpoint"
        private val logger = thisLogger()
    }

    @Serializable
    data class Args(
        @property:LLMDescription("Optional action: create, edit, or delete. Defaults to create")
        val action: String? = null,

        @property:LLMDescription("Path relative to project root")
        @SerialName("file_path")
        val filePath: String,

        @property:LLMDescription("1-based line number")
        val line: Int,

        @property:LLMDescription("Optional: breakpoint type id to use")
        @SerialName("breakpoint_type")
        val breakpointType: String? = null,

        @property:LLMDescription("Optional: condition expression for conditional breakpoint")
        val condition: String? = null,
    )

    @Serializable
    data class BreakpointDetails(
        @SerialName("file_path") val filePath: String,
        val line: Int,
        val breakpointType: String?,
        val condition: String?,
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val message: String,
            val breakpoint: BreakpointDetails,
        ) : Result()

        @Serializable
        data class Error(val message: String) : Result()
    }

    override suspend fun doExecute(args: Args): Result {
        return withContext(Dispatchers.Default) {
            try {
                val resolved = resolveLocation(project, args.filePath, args.line)
                    ?: return@withContext Result.Error("File not found: ${args.filePath}")
                val manager = XDebuggerManager.getInstance(project).breakpointManager

                when ((args.action ?: "create").lowercase()) {
                    "create" -> createBreakpoint(manager, resolved, args)
                    "edit" -> editBreakpoint(manager, resolved, args)
                    "delete" -> deleteBreakpoint(manager, resolved, args)
                    else -> Result.Error("Unsupported action: ${args.action}")
                }
            } catch (e: Exception) {
                logger.warn("breakpoint operation failed: ${e.message}", e)
                Result.Error("Failed to manage breakpoint: ${e.message}")
            }
        }
    }

    private fun createBreakpoint(
        manager: XBreakpointManager,
        resolved: ResolvedLocation,
        args: Args,
    ): Result {
        var createdBreakpoint: XLineBreakpoint<*>? = null
        var errorMessage: String? = null
        var finalLineIndex: Int? = null

        ApplicationManager.getApplication().invokeAndWait {
            runWriteAction {
                try {
                    val target = resolveBreakpointTarget(resolved, args)
                    if (target == null) {
                        errorMessage =
                            "Cannot add breakpoint on ${args.filePath}:${args.line}. No executable line found nearby."
                        return@runWriteAction
                    }

                    finalLineIndex = target.lineIndex
                    @Suppress("UNCHECKED_CAST")
                    val typedType =
                        target.breakpointType as XLineBreakpointType<XBreakpointProperties<*>>
                    val properties = typedType.createBreakpointProperties(
                        resolved.virtualFile,
                        target.lineIndex
                    )
                    val breakpoint = manager.addLineBreakpoint(
                        typedType,
                        resolved.fileUrl,
                        target.lineIndex,
                        properties
                    )
                    args.condition?.let(breakpoint::setCondition)
                    createdBreakpoint = breakpoint
                } catch (e: Exception) {
                    logger.warn("Failed to create breakpoint: ${e.message}", e)
                    errorMessage = e.message ?: "Unknown error"
                }
            }
        }

        val breakpoint = createdBreakpoint
            ?: return Result.Error(
                errorMessage ?: "Failed to create breakpoint at ${args.filePath}:${args.line}"
            )

        val resolvedLine = finalLineIndex?.plus(1) ?: (breakpoint.line + 1)
        val locationSuffix = if (resolvedLine == args.line) {
            ""
        } else {
            " (resolved to line $resolvedLine)"
        }

        return Result.Success(
            message = "Breakpoint created at ${args.filePath}:$resolvedLine$locationSuffix",
            breakpoint = breakpoint.toDetails(),
        )
    }

    private fun editBreakpoint(
        manager: XBreakpointManager,
        resolved: ResolvedLocation,
        args: Args,
    ): Result {
        val breakpoint =
            findLineBreakpoint(manager, resolved.fileUrl, resolved.lineIndex, args.breakpointType)
                ?: return Result.Error("No breakpoint found at ${args.filePath}:${args.line}")

        ApplicationManager.getApplication().invokeAndWait {
            runWriteAction {
                breakpoint.setCondition(args.condition)
            }
        }

        return Result.Success(
            message = "Breakpoint updated at ${args.filePath}:${args.line}",
            breakpoint = breakpoint.toDetails(),
        )
    }

    private fun deleteBreakpoint(
        manager: XBreakpointManager,
        resolved: ResolvedLocation,
        args: Args,
    ): Result {
        val breakpoint =
            findLineBreakpoint(manager, resolved.fileUrl, resolved.lineIndex, args.breakpointType)
                ?: return Result.Error("No breakpoint found at ${args.filePath}:${args.line}")
        val details = breakpoint.toDetails()

        ApplicationManager.getApplication().invokeAndWait {
            runWriteAction {
                manager.removeBreakpoint(breakpoint)
            }
        }

        return Result.Success(
            message = "Breakpoint deleted at ${args.filePath}:${args.line}",
            breakpoint = details,
        )
    }

    private fun selectBreakpointType(typeId: String?): XLineBreakpointType<*>? {
        @Suppress("UNCHECKED_CAST")
        val types =
            XLineBreakpointType.EXTENSION_POINT_NAME.extensionList as List<XLineBreakpointType<*>>
        return if (!typeId.isNullOrBlank()) {
            types.firstOrNull { it.id == typeId }
        } else {
            types.firstOrNull()
        }
    }

    private data class BreakpointTarget(
        val breakpointType: XLineBreakpointType<*>,
        val lineIndex: Int,
    )

    private fun resolveBreakpointTarget(
        resolved: ResolvedLocation,
        args: Args,
    ): BreakpointTarget? {
        val requestedType = selectBreakpointType(args.breakpointType)
        val candidateTypes = if (requestedType != null) {
            listOf(requestedType)
        } else {
            @Suppress("UNCHECKED_CAST")
            XLineBreakpointType.EXTENSION_POINT_NAME.extensionList as List<XLineBreakpointType<*>>
        }
        val document =
            FileDocumentManager.getInstance().getDocument(resolved.virtualFile) ?: return null
        val maxLineIndex = (document.lineCount - 1).coerceAtLeast(0)
        val startLine = resolved.lineIndex.coerceIn(0, maxLineIndex)
        val lineCandidates = candidateLineIndexes(document, startLine, maxLineIndex)

        for (lineIndex in lineCandidates) {
            val supportingTypes = candidateTypes.filter { breakpointType ->
                try {
                    breakpointType.canPutAt(resolved.virtualFile, lineIndex, project)
                } catch (_: Throwable) {
                    false
                }
            }

            val canPut = try {
                XDebuggerUtil.getInstance()
                    .canPutBreakpointAt(project, resolved.virtualFile, lineIndex)
            } catch (_: Throwable) {
                false
            }

            if (supportingTypes.isNotEmpty() && canPut) {
                return BreakpointTarget(supportingTypes.first(), lineIndex)
            }
        }

        // Fallback: be permissive and place breakpoint at requested line using a candidate type
        val fallbackLine = startLine
        val fallbackType = requestedType ?: run {
            @Suppress("UNCHECKED_CAST")
            (XLineBreakpointType.EXTENSION_POINT_NAME.extensionList as List<XLineBreakpointType<*>>).firstOrNull()
        }
        return fallbackType?.let { BreakpointTarget(it, fallbackLine) }
    }

    private fun candidateLineIndexes(
        document: Document,
        startLine: Int,
        maxLineIndex: Int,
        searchRadius: Int = 64,
        includeBlank: Boolean = true
    ): List<Int> {
        if (maxLineIndex < 0) {
            return emptyList()
        }

        val candidates = linkedSetOf<Int>()
        fun addIfExecutable(lineIndex: Int) {
            if (lineIndex !in 0..maxLineIndex) {
                return
            }
            if (includeBlank || !isBlankLine(document, lineIndex)) {
                candidates.add(lineIndex)
            }
        }

        addIfExecutable(startLine)
        for (offset in 1..searchRadius) {
            addIfExecutable(startLine + offset)
            addIfExecutable(startLine - offset)
        }
        return candidates.toList()
    }

    private fun isBlankLine(document: Document, lineIndex: Int): Boolean {
        val startOffset = document.getLineStartOffset(lineIndex)
        val endOffset = document.getLineEndOffset(lineIndex)
        return document.charsSequence.subSequence(startOffset, endOffset).isBlank()
    }

    private fun findLineBreakpoint(
        manager: XBreakpointManager,
        fileUrl: String,
        lineIndex: Int,
        breakpointType: String?,
    ): XLineBreakpoint<*>? {
        return manager.allBreakpoints
            .asSequence()
            .filterIsInstance<XLineBreakpoint<*>>()
            .firstOrNull {
                it.fileUrl == fileUrl &&
                        it.line == lineIndex &&
                        (breakpointType.isNullOrBlank() || it.type.id == breakpointType)
            }
    }

    private fun XLineBreakpoint<*>.toDetails(): BreakpointDetails {
        return BreakpointDetails(
            filePath = presentableFilePath,
            line = line + 1,
            breakpointType = type.id,
            condition = null,
        )
    }

    private data class ResolvedLocation(
        val virtualFile: VirtualFile,
        val fileUrl: String,
        val lineIndex: Int,
    )

    private fun resolveLocation(
        targetProject: Project,
        filePath: String,
        line: Int
    ): ResolvedLocation? {
        val psiFile: PsiFile = IdeToolUtils.getPsiFile(targetProject, filePath) ?: return null
        val virtualFile = psiFile.virtualFile ?: return null
        return ResolvedLocation(
            virtualFile = virtualFile,
            fileUrl = virtualFile.url,
            lineIndex = (line - 1).coerceAtLeast(0),
        )
    }

    override fun createDeniedResult(originalArgs: Args, deniedReason: String): Result {
        return Result.Error("Tool execution denied: $deniedReason")
    }

    override fun encodeResultToString(result: Result, serializer: JSONSerializer): String {
        return when (result) {
            is Result.Success -> result.message
            is Result.Error -> "Error: ${result.message}"
        }
    }
}
