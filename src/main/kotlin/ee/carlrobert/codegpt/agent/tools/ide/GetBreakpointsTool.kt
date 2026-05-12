package ee.carlrobert.codegpt.agent.tools.ide

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.JSONSerializer
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import ee.carlrobert.codegpt.agent.tools.BaseTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tool for retrieving current line breakpoints in the project.
 *
 * Returns file path, 1-based line, breakpoint type id, condition, enabled state
 * and some basic metadata for each line breakpoint.
 */
class GetBreakpointsTool(
    private val project: Project,
    sessionId: String,
    hookManager: HookManager,
) : BaseTool<GetBreakpointsTool.Args, GetBreakpointsTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    name = NAME,
    description = """
        Retrieves current line breakpoints in the project. Returns a list of breakpoints with file path, 1-based line number, breakpoint type id, condition (if any), enabled state and hit/other metadata when available.
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId,
) {

    companion object {
        const val NAME = "get_breakpoints"
        private val logger = thisLogger()
    }

    @Serializable
    data class Args(
        @property:LLMDescription("Optional: project path for multi-project scenarios")
        @SerialName("project_path")
        val projectPath: String? = null
    )

    @Serializable
    data class BreakpointInfo(
        @SerialName("file_path") val filePath: String,
        val line: Int,
        @SerialName("breakpoint_type") val breakpointType: String?,
        val enabled: Boolean = true
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val message: String,
            @SerialName("total_count") val totalCount: Int,
            val breakpoints: List<BreakpointInfo> = emptyList()
        ) : Result()

        @Serializable
        data class Error(val message: String) : Result()
    }

    override suspend fun doExecute(args: Args): Result {
        return withContext(Dispatchers.Default) {
            try {
                val targetProject = IdeToolUtils.getProject(args.projectPath, project)
                val manager = XDebuggerManager.getInstance(targetProject).breakpointManager
                val lineBreakpoints = manager.allBreakpoints
                    .asSequence()
                    .filterIsInstance<XLineBreakpoint<*>>()
                    .mapNotNull {
                        try {
                            BreakpointInfo(
                                filePath = it.presentableFilePath,
                                line = it.line + 1,
                                breakpointType = it.type.id,
                                enabled = it.isEnabled
                            )
                        } catch (e: Exception) {
                            logger.warn("Failed to read breakpoint details: ${e.message}", e)
                            null
                        }
                    }
                    .toList()

                val message = if (lineBreakpoints.isEmpty()) {
                    "No line breakpoints found in project '${targetProject.name}'"
                } else {
                    "Found ${lineBreakpoints.size} line breakpoint(s) in project '${targetProject.name}'"
                }

                Result.Success(
                    message = message,
                    totalCount = lineBreakpoints.size,
                    breakpoints = lineBreakpoints
                )
            } catch (e: Exception) {
                logger.warn("Failed to retrieve breakpoints: ${e.message}", e)
                Result.Error("Failed to retrieve breakpoints: ${e.message}")
            }
        }
    }

    override fun createDeniedResult(originalArgs: Args, deniedReason: String): Result {
        return Result.Error("Tool execution denied: $deniedReason")
    }

    override fun encodeResultToString(result: Result, serializer: JSONSerializer): String {
        return when (result) {
            is Result.Success -> {
                buildString {
                    appendLine(result.message)
                    if (result.breakpoints.isNotEmpty()) {
                        appendLine()
                        appendLine("Breakpoints:")
                        result.breakpoints.forEach { bp ->
                            append("- ${bp.filePath}:${bp.line}")
                            append(" — type=${bp.breakpointType ?: "unknown"}")
                            append(" — enabled=${bp.enabled}")
                            appendLine()
                        }
                    }
                }.truncateToolResult()
            }

            is Result.Error -> "Error: ${result.message}".truncateToolResult()
        }
    }
}
