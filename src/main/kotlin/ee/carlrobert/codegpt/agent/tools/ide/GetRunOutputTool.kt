package ee.carlrobert.codegpt.agent.tools.ide

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.JSONSerializer
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.tools.BaseTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tool for retrieving output from running processes.
 *
 * Shows active process information and status of run configurations.
 */
class GetRunOutputTool(
    private val project: Project,
    sessionId: String,
    hookManager: HookManager,
) : BaseTool<GetRunOutputTool.Args, GetRunOutputTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    name = NAME,
    description = """
        Retrieves information about running processes and their output.
        Shows active processes, their status, and associated run configurations.
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId,
) {

    companion object {
        const val NAME = "get_run_output"
        private val logger = thisLogger()
    }

    @Serializable
    data class Args(
        @property:LLMDescription("Optional: project path for multi-project scenarios")
        @SerialName("project_path")
        val projectPath: String? = null
    )

    @Serializable
    data class ProcessInfo(
        @SerialName("configuration_name")
        val configurationName: String?,
        @SerialName("is_running")
        val isRunning: Boolean,
        @SerialName("exit_code")
        val exitCode: Int?
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val message: String,
            @SerialName("active_processes")
            val activeProcesses: Int = 0,
            val processes: List<ProcessInfo> = emptyList()
        ) : Result()

        @Serializable
        data class Error(val message: String) : Result()
    }

    override suspend fun doExecute(args: Args): Result {
        return withContext(Dispatchers.Default) {
            try {
                val targetProject = IdeToolUtils.getProject(args.projectPath, project)
                val runContentManager = RunContentManager.getInstance(targetProject)

                val processes = mutableListOf<ProcessInfo>()
                var activeCount = 0

                try {
                    val descriptors = runContentManager.allDescriptors
                    for (descriptor in descriptors) {
                        val processHandler = descriptor.processHandler
                        if (processHandler != null) {
                            val isRunning = !processHandler.isProcessTerminated
                            if (isRunning) {
                                activeCount++
                            }
                            processes.add(
                                ProcessInfo(
                                    configurationName = descriptor.displayName,
                                    isRunning = isRunning,
                                    exitCode = if (isRunning) null else processHandler.exitCode
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Could not retrieve all process information: ${e.message}")
                }

                Result.Success(
                    message = "Found $activeCount active process(es)",
                    activeProcesses = activeCount,
                    processes = processes
                )
            } catch (e: Exception) {
                logger.warn("Failed to retrieve run output: ${e.message}", e)
                Result.Error("Failed to retrieve run output: ${e.message}")
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
                    if (result.processes.isNotEmpty()) {
                        appendLine("\nProcesses:")
                        result.processes.forEach { process ->
                            append("  - ")
                            append(process.configurationName ?: "Unknown")
                            append(" (")
                            if (process.isRunning) {
                                append("running")
                            } else {
                                append("exit code: ${process.exitCode}")
                            }
                            appendLine(")")
                        }
                    }
                }
            }

            is Result.Error -> "Error: ${result.message}"
        }
    }
}
