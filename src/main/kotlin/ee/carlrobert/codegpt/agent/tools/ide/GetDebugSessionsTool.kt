package ee.carlrobert.codegpt.agent.tools.ide

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.JSONSerializer
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import ee.carlrobert.codegpt.agent.tools.BaseTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tool that lists active debug sessions and basic session/runtime information.
 *
 * This returns session name/id, whether it is currently paused (has a stack frame),
 * current source position (file + 1-based line) when available, and an optional
 * presentation of the current stack frame.
 */
class GetDebugSessionsTool(
    private val project: Project,
    sessionId: String,
    hookManager: HookManager,
) : BaseTool<GetDebugSessionsTool.Args, GetDebugSessionsTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    name = NAME,
    description = """
        Retrieves active debug sessions in the project. For each session returns:
        - session_name: human-readable session name
        - is_paused: whether the session currently has a suspended stack frame
        - status: paused, running, or stopped
        - current_file: file path of the current source position when paused (nullable)
        - current_line: 1-based line number of the current position when paused (nullable)
        - current_frame: textual presentation of the current stack frame when available (nullable)
        - variables: visible variables for the paused frame including rendered type/value when available
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId,
) {

    companion object {
        const val NAME = "get_debug_sessions"
        private val logger = thisLogger()
    }

    @Serializable
    data class Args(
        @property:LLMDescription("Optional: project path for multi-project scenarios")
        @SerialName("project_path")
        val projectPath: String? = null
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val message: String,
            @SerialName("total_count") val totalCount: Int,
            val sessions: List<DebugSessionSnapshot> = emptyList()
        ) : Result()

        @Serializable
        data class Error(val message: String) : Result()
    }

    override suspend fun doExecute(args: Args): Result {
        return withContext(Dispatchers.Default) {
            try {
                val targetProject = IdeToolUtils.getProject(args.projectPath, project)
                val manager = XDebuggerManager.getInstance(targetProject)
                val infos = manager.debugSessions.mapNotNull { session ->
                    try {
                        DebugSessionSnapshotSupport.snapshot(session)
                    } catch (e: Exception) {
                        logger.warn("Failed to read debug session details: ${e.message}", e)
                        null
                    }
                }

                val message = if (infos.isEmpty()) {
                    "No active debug sessions in project '${targetProject.name}'"
                } else {
                    "Found ${infos.size} active debug session(s) in project '${targetProject.name}'"
                }

                Result.Success(
                    message = message,
                    totalCount = infos.size,
                    sessions = infos
                )
            } catch (e: Exception) {
                logger.warn("Failed to retrieve debug sessions: ${e.message}", e)
                Result.Error("Failed to retrieve debug sessions: ${e.message}")
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
                    if (result.sessions.isNotEmpty()) {
                        appendLine()
                        appendLine("Sessions:")
                        result.sessions.forEach { s ->
                            append("- ${s.sessionName}")
                            append(" — paused=${s.isPaused}")
                            if (!s.currentFile.isNullOrBlank()) {
                                append(" — ${s.currentFile}:${s.currentLine ?: "?"}")
                            }
                            if (!s.currentFrame.isNullOrBlank()) {
                                append(" — frame=${s.currentFrame}")
                            }
                            appendLine()
                            if (s.variables.isNotEmpty()) {
                                s.variables.forEach { variable ->
                                    append("  * ${variable.name}")
                                    if (!variable.type.isNullOrBlank()) {
                                        append(": ${variable.type}")
                                    }
                                    if (!variable.value.isNullOrBlank()) {
                                        append(" = ${variable.value}")
                                    }
                                    if (variable.hasChildren) {
                                        append(" (has children)")
                                    }
                                    appendLine()
                                }
                            }
                        }
                    }
                }.truncateToolResult()
            }

            is Result.Error -> "Error: ${result.message}".truncateToolResult()
        }
    }
}
