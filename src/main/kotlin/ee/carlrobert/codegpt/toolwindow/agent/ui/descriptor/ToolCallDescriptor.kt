package ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.Component
import javax.swing.Icon

enum class ToolKind {
    READ,
    WRITE,
    EDIT,
    BASH,
    BASH_OUTPUT,
    KILL_SHELL,
    SEARCH,
    WEB,
    TASK,
    TODO_WRITE,
    MCP,
    LIBRARY_RESOLVE,
    LIBRARY_DOCS,
    SKILL,
    ASK_QUESTION,
    EXIT,
    DIAGNOSTICS,
    IDE_RUN_CONFIGURATIONS,
    IDE_EXECUTE_RUN_CONFIGURATION,
    IDE_BREAKPOINT,
    IDE_BREAKPOINTS,
    IDE_DEBUG_SESSIONS,
    IDE_RUN_OUTPUT,
    IDE_DEBUG_SESSION_CONTROL,
    OTHER
}

data class Badge(
    val text: String,
    val color: JBColor = JBColor.GRAY,
    val tooltip: String? = null,
    val action: (() -> Unit)? = null
)

data class FileLink(
    val path: String,
    val displayName: String,
    val enabled: Boolean = true,
    val action: ((Project) -> Unit)? = null,
    val line: Int? = null,
    val column: Int? = null
)

data class ToolAction(
    val name: String,
    val icon: Icon,
    val action: (Component) -> Unit
)

data class FileChangeSnapshot(
    val beforeText: String,
    val afterText: String,
    val isNewFile: Boolean = false
)

data class ToolCallDiffPreview(
    val filePath: String,
    val snapshot: FileChangeSnapshot
)

enum class ToolCallSecondaryLayout {
    SINGLE_ROW,
    STACKED
}

data class ToolCallDescriptor(
    val kind: ToolKind,
    val icon: Icon?,
    val titlePrefix: String,
    val titleMain: String,
    val subtitleText: String? = null,
    val tooltip: String?,
    val secondaryBadges: List<Badge> = emptyList(),
    val fileLink: FileLink? = null,
    val actions: List<ToolAction> = emptyList(),
    val supportsStreaming: Boolean = false,
    val args: Any,
    val result: Any? = null,
    val projectId: String? = null,
    val prefixColor: JBColor? = null,
    val summary: String? = null,
    val detailText: String? = null,
    val secondaryLayout: ToolCallSecondaryLayout = ToolCallSecondaryLayout.SINGLE_ROW,
    val diffPreview: ToolCallDiffPreview? = null,
)
