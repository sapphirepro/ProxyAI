package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.typeToken
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.tools.ide.BreakpointTool
import ee.carlrobert.codegpt.agent.tools.ide.DebugSessionControlTool
import ee.carlrobert.codegpt.settings.skills.SkillDiscoveryService
import ee.carlrobert.codegpt.agent.tools.ide.ExecuteRunConfigurationTool

class ConfirmingWriteTool(
    private val delegate: WriteTool,
    private val approve: suspend (name: String, details: String) -> Boolean
) : Tool<WriteTool.Args, WriteTool.Result>(
    argsType = typeToken<WriteTool.Args>(),
    resultType = typeToken<WriteTool.Result>(),
    name = delegate.name,
    description = delegate.descriptor.description
) {

    override suspend fun execute(args: WriteTool.Args): WriteTool.Result {
        val details = buildString {
            append("Path: ")
            append(args.filePath)
            append("\n")
            append("Bytes: ")
            append(args.content.toByteArray().size)
        }
        val ok = approve("Write", details)
        if (!ok) {
            return WriteTool.Result.Error(
                filePath = args.filePath,
                error = "User rejected write operation"
            )
        }
        return delegate.execute(args)
    }
}

class ConfirmingLoadSkillTool(
    private val delegate: LoadSkillTool,
    private val project: Project,
    private val approve: suspend (name: String, details: String) -> Boolean
) : Tool<LoadSkillTool.Args, LoadSkillTool.Result>(
    argsType = typeToken<LoadSkillTool.Args>(),
    resultType = typeToken<LoadSkillTool.Result>(),
    name = delegate.name,
    description = delegate.descriptor.description
) {

    override suspend fun execute(args: LoadSkillTool.Args): LoadSkillTool.Result {
        val requested = args.skillName.trim()
        val skill = project.service<SkillDiscoveryService>().listSkills().firstOrNull {
            it.name.equals(requested, ignoreCase = true) ||
                    it.title.equals(requested, ignoreCase = true)
        }
        if (skill != null) {
            val promptTitle = "Load skill ${skill.name} into context"
            val ok = approve(promptTitle, skill.description)
            if (!ok) {
                return LoadSkillTool.Result.Error("User rejected loading skill '${skill.name}'")
            }
        }
        return delegate.execute(args)
    }
}

class ConfirmingEditTool(
    private val delegate: EditTool,
    private val approve: suspend (name: String, details: String) -> Boolean
) : Tool<EditTool.Args, EditTool.Result>(
    argsType = typeToken<EditTool.Args>(),
    resultType = typeToken<EditTool.Result>(),
    name = delegate.name,
    description = delegate.descriptor.description
) {

    override suspend fun execute(args: EditTool.Args): EditTool.Result {
        val ok = approve("Edit", args.shortDescription)
        if (!ok) {
            return EditTool.Result.Error(
                filePath = args.filePath,
                error = "User rejected edit operation"
            )
        }
        return delegate.execute(args)
    }
}

class ConfirmingExecuteRunConfigurationTool(
    private val delegate: ExecuteRunConfigurationTool,
    private val approve: suspend (name: String, details: String) -> Boolean
) : Tool<ExecuteRunConfigurationTool.Args, ExecuteRunConfigurationTool.Result>(
    argsType = typeToken<ExecuteRunConfigurationTool.Args>(),
    resultType = typeToken<ExecuteRunConfigurationTool.Result>(),
    name = delegate.name,
    description = delegate.descriptor.description
) {

    override suspend fun execute(args: ExecuteRunConfigurationTool.Args): ExecuteRunConfigurationTool.Result {
        val details = buildString {
            append("Configuration: ")
            append(args.configurationName)
            append("\n")
            append("Executor: ")
            append(args.executorName)
            if (args.projectPath != null) {
                append("\n")
                append("Project: ")
                append(args.projectPath)
            }
        }
        val ok = approve("Execute Run Configuration", details)
        if (!ok) {
            return ExecuteRunConfigurationTool.Result.Error(
                "User rejected run configuration execution"
            )
        }
        return delegate.execute(args)
    }
}

class ConfirmingBreakpointTool(
    private val delegate: BreakpointTool,
    private val approve: suspend (name: String, details: String) -> Boolean
) : Tool<BreakpointTool.Args, BreakpointTool.Result>(
    argsType = typeToken<BreakpointTool.Args>(),
    resultType = typeToken<BreakpointTool.Result>(),
    name = delegate.name,
    description = delegate.descriptor.description
) {

    override suspend fun execute(args: BreakpointTool.Args): BreakpointTool.Result {
        val action = (args.action ?: "create").lowercase()
        val actionLabel = when (action) {
            "delete" -> "Remove Breakpoint"
            "edit" -> "Edit Breakpoint"
            else -> "Add Breakpoint"
        }
        val details = buildString {
            append("Action: ")
            append(
                when (action) {
                    "delete" -> "remove"
                    "edit" -> "edit"
                    else -> "add"
                }
            )
            append("\nFile: ")
            append(args.filePath)
            append("\nLine: ")
            append(args.line)
            args.breakpointType?.takeIf { it.isNotBlank() }?.let {
                append("\nType: ")
                append(it)
            }
            args.condition?.takeIf { it.isNotBlank() }?.let {
                append("\nCondition: ")
                append(it)
            }
        }
        val ok = approve(actionLabel, details)
        if (!ok) {
            return BreakpointTool.Result.Error(
                "User rejected breakpoint operation"
            )
        }
        return delegate.execute(args)
    }
}

class ConfirmingDebugSessionControlTool(
    private val delegate: DebugSessionControlTool,
    private val approve: suspend (name: String, details: String) -> Boolean
) : Tool<DebugSessionControlTool.Args, DebugSessionControlTool.Result>(
    argsType = typeToken<DebugSessionControlTool.Args>(),
    resultType = typeToken<DebugSessionControlTool.Result>(),
    name = delegate.name,
    description = delegate.descriptor.description
) {

    override suspend fun execute(args: DebugSessionControlTool.Args): DebugSessionControlTool.Result {
        val details = buildString {
            append("Action: ")
            append(args.action)
            args.sessionName?.takeIf { it.isNotBlank() }?.let {
                append("\nSession: ")
                append(it)
            }
            args.filePath?.takeIf { it.isNotBlank() }?.let {
                append("\nFile: ")
                append(it)
            }
            args.line?.let {
                append("\nLine: ")
                append(it)
            }
        }
        val ok = approve("Debug Session Control", details)
        if (!ok) {
            return DebugSessionControlTool.Result.Error(
                "User rejected debug session control operation"
            )
        }
        return delegate.execute(args)
    }
}
