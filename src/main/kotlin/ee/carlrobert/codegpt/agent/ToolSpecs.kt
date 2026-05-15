package ee.carlrobert.codegpt.agent

import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.TypeToken
import ai.koog.serialization.typeToken
import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.agent.tools.ide.*
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalType

enum class ToolName(
    val id: String,
    val argsType: TypeToken,
    val resultType: TypeToken,
    val aliases: Set<String> = emptySet(),
    val displayName: String = id,
    val isWrite: Boolean = false,
    val subagentSelectable: Boolean = true,
    val alwaysIncludeInSubagent: Boolean = false,
    val approvalType: ToolApprovalType = ToolApprovalType.GENERIC
) {
    READ(
        id = ReadTool.NAME,
        argsType = typeToken<ReadTool.Args>(),
        resultType = typeToken<ReadTool.Result>(),
        aliases = setOf("ReadFile"),
        displayName = "Read"
    ),
    WRITE(
        id = WriteTool.NAME,
        argsType = typeToken<WriteTool.Args>(),
        resultType = typeToken<WriteTool.Result>(),
        aliases = setOf("WriteFile"),
        displayName = "Write",
        isWrite = true,
        approvalType = ToolApprovalType.WRITE
    ),
    EDIT(
        id = EditTool.NAME,
        argsType = typeToken<EditTool.Args>(),
        resultType = typeToken<EditTool.Result>(),
        aliases = setOf("Replace", "ReplaceText"),
        displayName = "Edit",
        isWrite = true,
        approvalType = ToolApprovalType.EDIT
    ),
    BASH(
        id = BashTool.NAME,
        argsType = typeToken<BashTool.Args>(),
        resultType = typeToken<BashTool.Result>(),
        aliases = setOf("Execute", "Terminal", "RunShellCommand"),
        displayName = "Bash",
        isWrite = true,
        approvalType = ToolApprovalType.BASH
    ),
    BASH_OUTPUT(
        id = BashOutputTool.NAME,
        argsType = typeToken<BashOutputTool.Args>(),
        resultType = typeToken<BashOutputTool.Result>(),
        displayName = "BashOutput"
    ),
    KILL_SHELL(
        id = KillShellTool.NAME,
        argsType = typeToken<KillShellTool.Args>(),
        resultType = typeToken<KillShellTool.Result>(),
        displayName = "KillShell"
    ),
    INTELLIJ_SEARCH(
        id = IntelliJSearchTool.NAME,
        argsType = typeToken<IntelliJSearchTool.Args>(),
        resultType = typeToken<IntelliJSearchTool.Result>(),
        aliases = setOf("Search", "ListDirectory", "Glob", "Grep", "GrepSearch"),
        displayName = "IntelliJSearch"
    ),
    DIAGNOSTICS(
        id = DiagnosticsTool.NAME,
        argsType = typeToken<DiagnosticsTool.Args>(),
        resultType = typeToken<DiagnosticsTool.Result>(),
        displayName = "Diagnostics"
    ),
    WEB_SEARCH(
        id = WebSearchTool.NAME,
        argsType = typeToken<WebSearchTool.Args>(),
        resultType = typeToken<WebSearchTool.Result>(),
        aliases = setOf("GoogleWebSearch"),
        displayName = "WebSearch"
    ),
    WEB_FETCH(
        id = WebFetchTool.NAME,
        argsType = typeToken<WebFetchTool.Args>(),
        resultType = typeToken<WebFetchTool.Result>(),
        displayName = "WebFetch"
    ),
    MCP(
        id = McpTool.NAME,
        argsType = typeToken<McpTool.Args>(),
        resultType = typeToken<McpTool.Result>(),
        displayName = "MCP"
    ),
    RESOLVE_LIBRARY_ID(
        id = ResolveLibraryIdTool.NAME,
        argsType = typeToken<ResolveLibraryIdTool.Args>(),
        resultType = typeToken<ResolveLibraryIdTool.Result>(),
        displayName = "ResolveLibraryId"
    ),
    GET_LIBRARY_DOCS(
        id = GetLibraryDocsTool.NAME,
        argsType = typeToken<GetLibraryDocsTool.Args>(),
        resultType = typeToken<GetLibraryDocsTool.Result>(),
        displayName = "GetLibraryDocs"
    ),
    LOAD_SKILL(
        id = LoadSkillTool.NAME,
        argsType = typeToken<LoadSkillTool.Args>(),
        resultType = typeToken<LoadSkillTool.Result>(),
        displayName = "LoadSkill"
    ),
    TASK(
        id = TaskTool.NAME,
        argsType = typeToken<TaskTool.Args>(),
        resultType = typeToken<TaskTool.Result>(),
        displayName = "Task",
        subagentSelectable = false
    ),
    ASK_USER_QUESTION(
        id = AskUserQuestionTool.NAME,
        argsType = typeToken<AskUserQuestionTool.Args>(),
        resultType = typeToken<AskUserQuestionTool.Result>(),
        displayName = "AskUserQuestion",
        subagentSelectable = false
    ),
    TODO_WRITE(
        id = TodoWriteTool.NAME,
        argsType = typeToken<TodoWriteTool.Args>(),
        resultType = typeToken<String>(),
        aliases = setOf("TodoWriteTool"),
        displayName = "TodoWrite"
    ),
    GET_RUN_CONFIGURATIONS(
        id = GetRunConfigurationsTool.NAME,
        argsType = typeToken<GetRunConfigurationsTool.Args>(),
        resultType = typeToken<GetRunConfigurationsTool.Result>(),
        aliases = setOf("GetRunConfigurations"),
        displayName = "GetRunConfigurations"
    ),
    EXECUTE_RUN_CONFIGURATION(
        id = ExecuteRunConfigurationTool.NAME,
        argsType = typeToken<ExecuteRunConfigurationTool.Args>(),
        resultType = typeToken<ExecuteRunConfigurationTool.Result>(),
        aliases = setOf("ExecuteRunConfiguration"),
        displayName = "ExecuteRunConfiguration",
        isWrite = true
    ),
    GET_RUN_OUTPUT(
        id = GetRunOutputTool.NAME,
        argsType = typeToken<GetRunOutputTool.Args>(),
        resultType = typeToken<GetRunOutputTool.Result>(),
        aliases = setOf("GetRunOutput"),
        displayName = "GetRunOutput"
    ),
    GET_DEBUG_SESSIONS(
        id = GetDebugSessionsTool.NAME,
        argsType = typeToken<GetDebugSessionsTool.Args>(),
        resultType = typeToken<GetDebugSessionsTool.Result>(),
        aliases = setOf("GetDebugSessions"),
        displayName = "GetDebugSessions"
    ),
    GET_BREAKPOINTS(
        id = GetBreakpointsTool.NAME,
        argsType = typeToken<GetBreakpointsTool.Args>(),
        resultType = typeToken<GetBreakpointsTool.Result>(),
        aliases = setOf("GetBreakpoints"),
        displayName = "GetBreakpoints"
    ),
    BREAKPOINT(
        id = BreakpointTool.NAME,
        argsType = typeToken<BreakpointTool.Args>(),
        resultType = typeToken<BreakpointTool.Result>(),
        aliases = setOf("Breakpoint", "CreateBreakpoint"),
        displayName = "Breakpoint",
        isWrite = true
    ),
    DEBUG_SESSION_CONTROL(
        id = DebugSessionControlTool.NAME,
        argsType = typeToken<DebugSessionControlTool.Args>(),
        resultType = typeToken<DebugSessionControlTool.Result>(),
        displayName = "DebugSessionControl",
        isWrite = true
    ),
    EXIT(
        id = "Exit",
        argsType = typeToken<Unit>(),
        resultType = typeToken<Unit>(),
        displayName = "Exit",
        subagentSelectable = false,
        alwaysIncludeInSubagent = true
    );

    companion object {
        private val subagentTools = entries.filter { it.subagentSelectable }
        val readOnly: List<ToolName> = subagentTools.filterNot { it.isWrite }
        val write: List<ToolName> = subagentTools.filter { it.isWrite }

        private val byNormalizedName: Map<String, ToolName> = buildMap {
            ToolName.entries.forEach { tool ->
                put(normalize(tool.id), tool)
                put(normalize(tool.displayName), tool)
                tool.aliases.forEach { alias -> put(normalize(alias), tool) }
            }
        }

        fun fromString(value: String): ToolName? = byNormalizedName[normalize(value)]

        fun parse(values: Collection<String>): Set<ToolName> {
            return values.mapNotNull(::fromString).filterTo(linkedSetOf()) { it.subagentSelectable }
        }

        fun toStoredValues(tools: Collection<ToolName>): List<String> {
            val selected = tools.toSet()
            return subagentTools.filter { it in selected }.map { it.id }
        }

        private fun normalize(value: String): String {
            return value.lowercase().filter { it.isLetterOrDigit() }
        }
    }
}

object ToolSpecs {
    fun find(toolName: String): ToolName? = ToolName.fromString(toolName)

    fun approvalTypeFor(toolName: String): ToolApprovalType =
        find(toolName)?.approvalType ?: ToolApprovalType.GENERIC

    fun decodeArgsOrNull(
        toolName: String,
        payload: JSONObject,
    ) = decodeElementOrNull(find(toolName)?.argsType, payload)

    fun decodeArgsOrNull(
        toolName: String,
        payload: String,
    ) = decodeStringOrNull(find(toolName)?.argsType, payload)

    fun decodeResultOrNull(
        toolName: String,
        payload: JSONElement,
    ) = decodeElementOrNull(find(toolName)?.resultType, payload)

    fun decodeResultOrNull(
        toolName: String,
        payload: String,
    ) = decodeStringOrNull(find(toolName)?.resultType, payload)

    private fun decodeElementOrNull(
        type: TypeToken?,
        rawPayload: JSONElement,
    ): Any? {
        if (type == null) {
            return null
        }

        return runCatching {
            koogJsonSerializer.decodeFromJSONElement<Any>(rawPayload, type)
        }.getOrNull()
    }

    private fun decodeStringOrNull(
        type: TypeToken?,
        rawPayload: String,
    ): Any? {
        if (type == null || rawPayload.isBlank()) {
            return null
        }
        return runCatching {
            koogJsonSerializer.decodeFromString<Any>(rawPayload, type)
        }.recoverCatching {
            val normalizedPayload = normalizeToolArgumentsJson(rawPayload) ?: throw it
            koogJsonSerializer.decodeFromString(normalizedPayload, type)
        }.getOrNull()
    }
}
