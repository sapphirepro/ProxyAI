package ee.carlrobert.codegpt.agent.tools.ide

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.JSONSerializer
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
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
 * Tool for executing or stopping run configurations programmatically.
 *
 * This tool can either start a run configuration (default) or stop/kill
 * an already running configuration. Use `action` = "start" or "stop".
 */
class ExecuteRunConfigurationTool(
    private val project: Project,
    sessionId: String,
    hookManager: HookManager,
) : BaseTool<ExecuteRunConfigurationTool.Args, ExecuteRunConfigurationTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    name = NAME,
    description = """
        Executes or stops a run configuration in the IDE.
        The configuration must exist in the project. Use get_run_configurations to list available configurations.
        By default `action` is set to `start`. To stop a running configuration set `action` to `stop`.
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId,
) {

    companion object {
        const val NAME = "execute_run_configuration"
        private val logger = thisLogger()
    }

    @Serializable
    data class Args(
        @property:LLMDescription("Name of the run configuration to execute/stop")
        @SerialName("configuration_name")
        val configurationName: String,
        @property:LLMDescription("Optional: executor name ('Run' or 'Debug'). Defaults to 'Run'.")
        @SerialName("executor_name")
        val executorName: String = "Run",
        @property:LLMDescription("Optional: project path for multi-project scenarios")
        @SerialName("project_path")
        val projectPath: String? = null,
        @property:LLMDescription("Action to perform: 'start' or 'stop'. Defaults to 'start'.")
        val action: String = "start"
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val message: String,
            @SerialName("configuration_name")
            val configurationName: String,
            val action: String
        ) : Result()

        @Serializable
        data class Error(val message: String) : Result()
    }

    override suspend fun doExecute(args: Args): Result {
        return withContext(Dispatchers.Default) {
            try {
                val targetProject = IdeToolUtils.getProject(args.projectPath, project)

                val normalizedAction = args.action.trim().lowercase()
                if (normalizedAction == "stop" || normalizedAction == "kill" || normalizedAction == "terminate") {
                    // Attempt to stop/kill running processes for the given configuration name
                    val descriptors = try {
                        RunContentManager.getInstance(targetProject).allDescriptors
                    } catch (e: Exception) {
                        logger.warn("Could not access run descriptors: ${e.message}", e)
                        return@withContext Result.Error("Could not access run descriptors: ${e.message}")
                    }

                    val matching = descriptors.filter { it.displayName == args.configurationName }

                    if (matching.isEmpty()) {
                        return@withContext Result.Error(
                            "No running process found for '${args.configurationName}'. Available: ${
                                descriptors.joinToString(
                                    ", "
                                ) { it.displayName }
                            }"
                        )
                    }

                    var stopped = 0
                    val failed = mutableListOf<String>()
                    for (descriptor in matching) {
                        try {
                            val handler = descriptor.processHandler
                            if (handler == null) {
                                failed += "${descriptor.displayName}: no process handler"
                                continue
                            }
                            if (handler.isProcessTerminated) {
                                continue
                            }

                            try {
                                handler.destroyProcess()
                            } catch (_: Throwable) {
                                try {
                                    handler.destroyProcess()
                                } catch (t2: Throwable) {
                                    logger.warn(
                                        "Failed to destroy process for ${descriptor.displayName}: ${t2.message}",
                                        t2
                                    )
                                }
                            }
                            stopped++
                        } catch (e: Exception) {
                            logger.warn(
                                "Failed to stop process for ${descriptor.displayName}: ${e.message}",
                                e
                            )
                            failed += "${descriptor.displayName}: ${e.message}"
                        }
                    }

                    if (stopped > 0) {
                        return@withContext Result.Success(
                            message = "Stopped $stopped process(es) for '${args.configurationName}'",
                            configurationName = args.configurationName,
                            action = "stop"
                        )
                    }

                    return@withContext Result.Error(
                        "No processes were stopped for '${args.configurationName}'. Failures: ${
                            failed.joinToString(
                                "; "
                            )
                        }"
                    )
                }

                // Default: start
                val runManager = RunManager.getInstance(targetProject)

                val selectedSettings = runManager.allSettings.find {
                    it.name.equals(args.configurationName, ignoreCase = false)
                } ?: return@withContext Result.Error(
                    "Run configuration '${args.configurationName}' not found. Available: ${
                        runManager.allSettings.joinToString(", ") { it.name }
                    }"
                )

                if (selectedSettings.isTemplate) {
                    return@withContext Result.Error("Cannot execute template configuration '${args.configurationName}'")
                }

                val executor = ExecutorRegistry.getInstance().getExecutorById(args.executorName)
                    ?: return@withContext Result.Error("Executor '${args.executorName}' not found. Use 'Run' or 'Debug'.")

                ProgramRunnerUtil.executeConfiguration(selectedSettings, executor)

                Result.Success(
                    message = "Successfully started run configuration '${args.configurationName}'",
                    configurationName = args.configurationName,
                    action = "start"
                )
            } catch (e: Exception) {
                logger.warn("Failed to execute/stop run configuration: ${e.message}", e)
                Result.Error("Failed to execute/stop run configuration: ${e.message}")
            }
        }
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
