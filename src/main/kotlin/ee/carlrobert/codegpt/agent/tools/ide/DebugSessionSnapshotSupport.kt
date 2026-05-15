package ee.carlrobert.codegpt.agent.tools.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.NlsSafe
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon

@Serializable
enum class DebugSessionStatus {
    @SerialName("paused")
    PAUSED,

    @SerialName("running")
    RUNNING,

    @SerialName("stopped")
    STOPPED,
}

@Serializable
data class DebugVariableInfo(
    val name: String,
    val type: String? = null,
    val value: String? = null,
    @SerialName("has_children") val hasChildren: Boolean = false
)

@Serializable
data class DebugSessionSnapshot(
    @SerialName("session_name") val sessionName: String,
    @SerialName("is_paused") val isPaused: Boolean,
    val status: DebugSessionStatus,
    @SerialName("current_file") val currentFile: String? = null,
    @SerialName("current_line") val currentLine: Int? = null,
    @SerialName("current_frame") val currentFrame: String? = null,
    val variables: List<DebugVariableInfo> = emptyList(),
)

internal object DebugSessionSnapshotSupport {
    private const val MAX_VARIABLES = 25
    private const val VALUE_TIMEOUT_MS = 5000L
    private val logger = thisLogger()

    fun snapshot(session: XDebugSession, includeVariables: Boolean = true): DebugSessionSnapshot {
        val position = session.currentPosition
        val frame = session.currentStackFrame
        val isPaused = frame != null
        return DebugSessionSnapshot(
            sessionName = session.sessionName,
            isPaused = isPaused,
            status = if (isPaused) DebugSessionStatus.PAUSED else DebugSessionStatus.RUNNING,
            currentFile = position?.file?.path,
            currentLine = position?.line?.plus(1),
            currentFrame = frame?.toString(),
            variables = if (includeVariables && frame != null) {
                collectVariables(frame)
            } else {
                emptyList()
            }
        )
    }

    fun stoppedSnapshot(previousState: DebugSessionSnapshot): DebugSessionSnapshot {
        return previousState.copy(
            isPaused = false,
            status = DebugSessionStatus.STOPPED,
            currentFrame = null,
            variables = emptyList()
        )
    }

    private fun collectVariables(frame: XStackFrame): List<DebugVariableInfo> {
        return try {
            val collector = XDebuggerVariableCollector(::collectPresentation) { it.toString() }
            runOnEdtAndWait {
                frame.computeChildren(collector)
            }
            collector.awaitResults()
        } catch (t: Throwable) {
            logger.debug("Failed to collect generic debug variables", t)
            emptyList()
        }
    }

    private fun runOnEdtAndWait(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            action()
        } else {
            app.invokeAndWait(action)
        }
    }

    private fun collectPresentation(
        name: String,
        value: XValue,
        collector: XDebuggerVariableCollector
    ) {
        val node = XValuePresentationCollector(name, collector)
        try {
            runOnEdtAndWait {
                value.computePresentation(node, XValuePlace.TREE)
            }
        } catch (t: Throwable) {
            logger.debug("Failed to compute presentation for debug value '$name'", t)
            collector.onPresentationFailed(name, value)
        }
    }

    private fun renderPresentationText(presentation: XValuePresentation): String {
        val builder = StringBuilder()
        presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
            override fun renderValue(value: @NlsSafe String) {
                builder.append(value)
            }

            override fun renderStringValue(value: String) {
                builder.append(value)
            }

            override fun renderNumericValue(value: String) {
                builder.append(value)
            }

            override fun renderKeywordValue(value: String) {
                builder.append(value)
            }

            override fun renderStringValue(
                value: @NlsSafe String,
                additionalSpecialCharsToHighlight: @NlsSafe String?,
                maxLength: Int
            ) {
                builder.append(value).append(additionalSpecialCharsToHighlight)
            }

            override fun renderComment(comment: String) {
                if (builder.isNotEmpty()) {
                    builder.append(' ')
                }
                builder.append(comment)
            }

            override fun renderError(error: String) {
                if (builder.isNotEmpty()) {
                    builder.append(' ')
                }
                builder.append(error)
            }

            override fun renderSpecialSymbol(symbol: String) {
                builder.append(symbol)
            }

            override fun renderValue(value: String, attributes: TextAttributesKey) {
                builder.append(value)
            }
        })
        return builder.toString().ifBlank { "<unavailable>" }
    }

    private class XDebuggerVariableCollector(
        private val onCollectPresentation: (String, XValue, XDebuggerVariableCollector) -> Unit,
        private val onSafeValueText: (XValue) -> String
    ) : XCompositeNode {
        private val variables = mutableListOf<DebugVariableInfo>()
        private val completionLatch = CountDownLatch(1)
        private var pendingPresentations = 0
        private var receivedLastBatch = false

        override fun addChildren(children: XValueChildrenList, last: Boolean) {
            val remainingCapacity = (MAX_VARIABLES - variables.size).coerceAtLeast(0)
            val childCount = minOf(children.size(), remainingCapacity)
            if (last) {
                receivedLastBatch = true
            }

            for (index in 0 until childCount) {
                val name = children.getName(index)
                val value = children.getValue(index)
                if (name == null || value == null) {
                    continue
                }

                pendingPresentations += 1
                onCollectPresentation(name, value, this)
            }

            if (children.size() > childCount) {
                receivedLastBatch = true
            }

            completeIfFinished()
        }

        override fun tooManyChildren(count: Int) {
            receivedLastBatch = true
            completeIfFinished()
        }

        override fun setAlreadySorted(sorted: Boolean) {
        }

        override fun setErrorMessage(message: String) {
            receivedLastBatch = true
            completeIfFinished()
        }

        override fun setErrorMessage(message: String, hyperlink: XDebuggerTreeNodeHyperlink?) {
            receivedLastBatch = true
            completeIfFinished()
        }

        override fun setMessage(
            message: String,
            icon: Icon?,
            attributes: SimpleTextAttributes,
            hyperlink: XDebuggerTreeNodeHyperlink?
        ) {
            if (message.isNotBlank() && variables.isEmpty()) {
                variables += DebugVariableInfo(name = "message", value = message)
            }
            receivedLastBatch = true
            completeIfFinished()
        }

        fun onPresentationReady(variable: DebugVariableInfo) {
            variables += variable
            pendingPresentations = (pendingPresentations - 1).coerceAtLeast(0)
            completeIfFinished()
        }

        fun onPresentationFailed(name: String, value: XValue) {
            pendingPresentations = (pendingPresentations - 1).coerceAtLeast(0)
            variables += DebugVariableInfo(name = name, value = onSafeValueText(value))
            completeIfFinished()
        }

        fun awaitResults(): List<DebugVariableInfo> {
            val completed = completionLatch.await(VALUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed) {
                logger.debug("Timed out collecting debug variables after ${VALUE_TIMEOUT_MS}ms")
            }
            return variables.toList()
        }

        private fun completeIfFinished() {
            val shouldComplete = receivedLastBatch && pendingPresentations == 0
            if (shouldComplete) {
                completionLatch.countDown()
            }
        }
    }

    private class XValuePresentationCollector(
        private val name: String,
        private val collector: XDebuggerVariableCollector
    ) : XValueNode {

        private var reported = false

        override fun setPresentation(
            icon: Icon?,
            type: String?,
            value: String,
            hasChildren: Boolean
        ) {
            report(DebugVariableInfo(name, type, value, hasChildren))
        }

        override fun setPresentation(
            icon: Icon?,
            presentation: XValuePresentation,
            hasChildren: Boolean
        ) {
            val renderedValue = renderPresentationText(presentation)
            report(DebugVariableInfo(name, presentation.type, renderedValue, hasChildren))
        }

        override fun setFullValueEvaluator(evaluator: XFullValueEvaluator) {
        }

        private fun report(variable: DebugVariableInfo) {
            if (reported) {
                return
            }
            reported = true
            collector.onPresentationReady(variable)
        }
    }
}
