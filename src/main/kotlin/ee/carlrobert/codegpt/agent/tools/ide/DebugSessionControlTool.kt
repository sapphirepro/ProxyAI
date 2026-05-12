package ee.carlrobert.codegpt.agent.tools.ide

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.JSONSerializer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import ee.carlrobert.codegpt.agent.tools.BaseTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

class DebugSessionControlTool(
    private val project: Project,
    sessionId: String,
    hookManager: HookManager,
) : BaseTool<DebugSessionControlTool.Args, DebugSessionControlTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    name = NAME,
    description = """
        Controls an active IDE debug session.
        Supported actions: pause, resume, step_into, force_step_into, step_out, step_over, stop, show_execution_point, run_to_position.
        Use session_name to target a specific debug session when more than one is active.
        Returns before_state and the latest after_state for the targeted session after each action.
        run_to_position requires file_path and line.
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId,
) {

    companion object {
        const val NAME = "debug_session_control"
        private val logger = thisLogger()
        private const val STATE_POLL_ATTEMPTS = 5
        private const val STATE_POLL_DELAY_MS = 100L
    }

    @Serializable
    data class Args(
        @property:LLMDescription("Action to perform: pause, resume, step_into, force_step_into, step_out, step_over, stop, show_execution_point, or run_to_position")
        val action: String,

        @property:LLMDescription("Optional: name of the target debug session. Required when multiple debug sessions are active")
        @SerialName("session_name")
        val sessionName: String? = null,

        @property:LLMDescription("Optional: project path for multi-project scenarios")
        @SerialName("project_path")
        val projectPath: String? = null,

        @property:LLMDescription("Optional: target file path relative to project root, used for run_to_position")
        @SerialName("file_path")
        val filePath: String? = null,

        @property:LLMDescription("Optional: 1-based line number, used for run_to_position")
        val line: Int? = null,

        @property:LLMDescription("Optional: 1-based column number, used for run_to_position")
        val column: Int? = null,

        @property:LLMDescription("Optional: when true, use the force/ignore-breakpoints variant if supported by the action")
        val force: Boolean? = null,
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val message: String,
            @SerialName("session_name") val sessionName: String,
            val action: String,
            @SerialName("before_state") val beforeState: DebugSessionSnapshot,
            @SerialName("after_state") val afterState: DebugSessionSnapshot,
        ) : Result()

        @Serializable
        data class Error(val message: String) : Result()
    }

    override suspend fun doExecute(args: Args): Result {
        return withContext(Dispatchers.Default) {
            try {
                val targetProject = IdeToolUtils.getProject(args.projectPath, project)
                val session = resolveSession(targetProject, args.sessionName)
                    ?: return@withContext missingSessionResult(targetProject, args.sessionName)

                val normalizedAction = args.action.lowercase()
                val beforeState = DebugSessionSnapshotSupport.snapshot(session)
                validatePreconditions(session, normalizedAction, args)?.let { error ->
                    return@withContext Result.Error(error)
                }

                val actionMessage = executeAction(targetProject, session, normalizedAction, args)
                    ?: return@withContext Result.Error("Unsupported action: ${args.action}")

                val afterState = awaitUpdatedState(targetProject, session, normalizedAction, beforeState)

                Result.Success(
                    message = actionMessage,
                    sessionName = session.sessionName,
                    action = normalizedAction,
                    beforeState = beforeState,
                    afterState = afterState,
                )
            } catch (e: Exception) {
                logger.warn("Failed to control debug session: ${e.message}", e)
                Result.Error("Failed to control debug session: ${e.message}")
            }
        }
    }

    private fun missingSessionResult(targetProject: Project, sessionName: String?): Result.Error {
        val sessions = XDebuggerManager.getInstance(targetProject).debugSessions.toList()
        if (sessions.isEmpty()) {
            return Result.Error("No active debug sessions in project '${targetProject.name}'")
        }
        if (sessionName.isNullOrBlank()) {
            val available = sessions.joinToString(", ") { it.sessionName }
            return Result.Error("Multiple debug sessions are active. Specify session_name. Available sessions: $available")
        }
        val available = sessions.joinToString(", ") { it.sessionName }
        return Result.Error("Debug session '$sessionName' was not found. Available sessions: $available")
    }

    private fun resolveSession(targetProject: Project, sessionName: String?): XDebugSession? {
        val sessions = XDebuggerManager.getInstance(targetProject).debugSessions.toList()
        if (sessions.isEmpty()) {
            return null
        }
        if (sessionName.isNullOrBlank()) {
            return sessions.singleOrNull()
        }

        val matches = sessions.filter { it.sessionName == sessionName }
        return if (matches.size == 1) matches.first() else null
    }

    private fun validatePreconditions(session: XDebugSession, action: String, args: Args): String? {
        return when (action) {
            "pause" -> if (session.isSuspended) "Debug session '${session.sessionName}' is already paused" else null
            "resume" -> if (!session.isSuspended) "Debug session '${session.sessionName}' is not paused" else null
            "step_into", "force_step_into", "step_out", "step_over", "run_to_position" -> {
                if (!session.isSuspended) {
                    "Debug session '${session.sessionName}' must be paused before performing '$action'"
                } else if (action == "run_to_position" && (args.filePath.isNullOrBlank() || args.line == null)) {
                    "run_to_position requires file_path and line"
                } else {
                    null
                }
            }

            "show_execution_point" -> if (session.currentPosition == null) "Debug session '${session.sessionName}' has no current execution position" else null
            "stop" -> null
            else -> null
        }
    }

    private fun executeAction(
        targetProject: Project,
        session: XDebugSession,
        action: String,
        args: Args,
    ): String? {
        return when (action) {
            "pause" -> {
                invokeOnEdtAndWait { session.pause() }
                "Paused debug session '${session.sessionName}'"
            }

            "resume" -> {
                invokeOnEdtAndWait { session.resume() }
                "Resumed debug session '${session.sessionName}'"
            }

            "step_into" -> {
                invokeOnEdtAndWait { session.stepInto() }
                "Stepped into in debug session '${session.sessionName}'"
            }

            "force_step_into" -> {
                invokeOnEdtAndWait { session.forceStepInto() }
                "Force stepped into in debug session '${session.sessionName}'"
            }

            "step_out" -> {
                invokeOnEdtAndWait { session.stepOut() }
                "Stepped out in debug session '${session.sessionName}'"
            }

            "step_over" -> {
                invokeOnEdtAndWait { session.stepOver(args.force == true) }
                "Stepped over in debug session '${session.sessionName}'"
            }

            "stop" -> {
                invokeOnEdtAndWait { session.stop() }
                "Stopped debug session '${session.sessionName}'"
            }

            "show_execution_point" -> {
                invokeOnEdtAndWait { session.showExecutionPoint() }
                "Showed execution point for debug session '${session.sessionName}'"
            }

            "run_to_position" -> {
                val sourcePosition =
                    createSourcePosition(targetProject, args.filePath!!, args.line!!, args.column)
                        ?: return "Failed to resolve source position ${args.filePath}:${args.line}"
                invokeOnEdtAndWait { session.runToPosition(sourcePosition, args.force == true) }
                "Running debug session '${session.sessionName}' to ${args.filePath}:${args.line}"
            }

            else -> null
        }
    }

    private fun createSourcePosition(
        targetProject: Project,
        filePath: String,
        line: Int,
        column: Int?
    ) = IdeToolUtils.getPsiFile(targetProject, filePath)?.virtualFile?.let { virtualFile ->
        XDebuggerUtil.getInstance().createPosition(
            virtualFile,
            (line - 1).coerceAtLeast(0),
            (column?.minus(1) ?: 0).coerceAtLeast(0)
        )
    }

    private suspend fun awaitUpdatedState(
        targetProject: Project,
        session: XDebugSession,
        action: String,
        previousState: DebugSessionSnapshot,
    ): DebugSessionSnapshot {
        if (action == "show_execution_point") {
            return DebugSessionSnapshotSupport.snapshot(session)
        }
        repeat(STATE_POLL_ATTEMPTS) {
            delay(STATE_POLL_DELAY_MS.milliseconds)
            val current = currentSnapshot(targetProject, session, previousState)
            if (current != previousState) {
                return current
            }
        }
        return currentSnapshot(targetProject, session, previousState)
    }

    private fun currentSnapshot(
        targetProject: Project,
        originalSession: XDebugSession,
        previousState: DebugSessionSnapshot,
    ): DebugSessionSnapshot {
        val activeSession = XDebuggerManager.getInstance(targetProject)
            .debugSessions
            .firstOrNull { it == originalSession || it.sessionName == previousState.sessionName }
        return if (activeSession != null) {
            DebugSessionSnapshotSupport.snapshot(activeSession)
        } else {
            DebugSessionSnapshotSupport.stoppedSnapshot(previousState)
        }
    }

    private fun invokeOnEdtAndWait(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            action()
        } else {
            app.invokeAndWait(action)
        }
    }

    override fun createDeniedResult(originalArgs: Args, deniedReason: String): Result {
        return Result.Error("Tool execution denied: $deniedReason")
    }

    override fun encodeResultToString(result: Result, serializer: JSONSerializer): String {
        return when (result) {
            is Result.Success -> buildString {
                appendLine(result.message)
                appendLine("Before: ${formatState(result.beforeState)}")
                append("After: ${formatState(result.afterState)}")
                if (result.afterState.variables.isNotEmpty()) {
                    appendLine()
                    appendLine("Variables:")
                    result.afterState.variables.forEach { variable ->
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

            is Result.Error -> "Error: ${result.message}"
        }
    }

    private fun formatState(state: DebugSessionSnapshot): String {
        val location = if (!state.currentFile.isNullOrBlank()) {
            "${state.currentFile}:${state.currentLine ?: "?"}"
        } else {
            "<unknown location>"
        }
        return "status=${state.status.name.lowercase()}, paused=${state.isPaused}, location=$location"
    }
}
