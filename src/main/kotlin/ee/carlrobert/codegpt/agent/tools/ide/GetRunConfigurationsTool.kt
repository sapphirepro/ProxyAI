package ee.carlrobert.codegpt.agent.tools.ide

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.JSONSerializer
import com.intellij.execution.RunManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.tools.BaseTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tool for retrieving all available run configurations in the current project.
 *
 * This tool queries the IntelliJ Platform's RunManager to list all configured run/debug configurations,
 * including their names, types, and basic settings.
 */
class GetRunConfigurationsTool(
    private val project: Project,
    sessionId: String,
    hookManager: HookManager,
) : BaseTool<GetRunConfigurationsTool.Args, GetRunConfigurationsTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    name = NAME,
    description = """
        Retrieves all available run configurations in the project.
        Returns a list of all run/debug configurations with their names and types (e.g., Java Application, Gradle, npm, etc.).
        Use this to discover available configurations before executing them.
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId,
) {

    companion object {
        const val NAME = "get_run_configurations"
        private val logger = thisLogger()
    }

    @Serializable
    data class Args(
        @property:LLMDescription("Optional: project path for multi-project scenarios")
        @SerialName("project_path")
        val projectPath: String? = null
    )

    @Serializable
    data class RunConfigurationInfo(
        val name: String,
        val type: String,
        @SerialName("is_template")
        val isTemplate: Boolean = false
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val message: String,
            @SerialName("total_count")
            val totalCount: Int,
            val configurations: List<RunConfigurationInfo> = emptyList()
        ) : Result()

        @Serializable
        data class Error(val message: String) : Result()
    }

    override suspend fun doExecute(args: Args): Result {
        return withContext(Dispatchers.Default) {
            try {
                val targetProject = IdeToolUtils.getProject(args.projectPath, project)
                val allConfigurations =
                    RunManager.getInstance(targetProject).allSettings.map { settings ->
                        val type = settings.configuration.javaClass.simpleName
                            .replace("Configuration", "")
                            .replace("RunConfiguration", "")
                        RunConfigurationInfo(
                            name = settings.name,
                            type = type,
                            isTemplate = settings.isTemplate
                        )
                    }

                val message = if (allConfigurations.isEmpty()) {
                    "No run configurations found in project '${targetProject.name}'"
                } else {
                    "Found ${allConfigurations.size} run configuration(s) in project '${targetProject.name}'"
                }

                Result.Success(
                    message = message,
                    totalCount = allConfigurations.size,
                    configurations = allConfigurations
                )
            } catch (e: Exception) {
                logger.warn("Failed to retrieve run configurations: ${e.message}", e)
                Result.Error("Failed to retrieve run configurations: ${e.message}")
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
                    if (result.configurations.isNotEmpty()) {
                        appendLine("\nAvailable configurations:")
                        result.configurations.forEach { config ->
                            append("  - ")
                            append(config.name)
                            append(" (")
                            append(config.type)
                            if (config.isTemplate) append(", template")
                            appendLine(")")
                        }
                    }
                }
            }

            is Result.Error -> "Error: ${result.message}"
        }
    }
}
