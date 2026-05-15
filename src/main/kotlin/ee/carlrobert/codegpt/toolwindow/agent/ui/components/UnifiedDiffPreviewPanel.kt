package ee.carlrobert.codegpt.toolwindow.agent.ui.components

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolCallDiffPreview
import java.awt.BorderLayout
import java.awt.Dimension

class UnifiedDiffPreviewPanel(
    project: Project?,
    preview: ToolCallDiffPreview
) : JBPanel<UnifiedDiffPreviewPanel>() {

    init {
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 0, 0)

        val viewer = createViewer(project, preview)
        val editor = viewer.editor
        editor.apply {
            settings.apply {
                additionalColumnsCount = 0
                additionalLinesCount = 0
                isAdditionalPageAtBottom = false
                isFoldingOutlineShown = false
                isCaretRowShown = false
                isBlinkCaret = false
                isDndEnabled = false
                isIndentGuidesShown = false
                isUseSoftWraps = false
            }
            scrollPane.border = JBUI.Borders.empty()
            scrollPane.viewportBorder = null
            contentComponent.border = JBUI.Borders.emptyLeft(4)
            setBorder(JBUI.Borders.empty())
            component.preferredSize = Dimension(0, JBUI.scale(180))
        }

        add(editor.component, BorderLayout.CENTER)
    }

    private fun createViewer(project: Project?, preview: ToolCallDiffPreview): UnifiedDiffViewer {
        val fileName = preview.filePath.substringAfterLast('/').substringAfterLast('\\')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val beforeFile = LightVirtualFile(
            fileName,
            fileType,
            StringUtil.convertLineSeparators(preview.snapshot.beforeText)
        )
        val afterFile = LightVirtualFile(
            fileName,
            fileType,
            StringUtil.convertLineSeparators(preview.snapshot.afterText)
        )

        val contentFactory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            null,
            contentFactory.create(project, beforeFile),
            contentFactory.create(project, afterFile),
            null,
            null
        )

        return UnifiedDiffViewer(PreviewDiffContext(project), request).apply {
            rediff(true)
        }
    }

    private class PreviewDiffContext(private val project: Project?) : DiffContext() {
        private val data = UserDataHolderBase()

        override fun getProject(): Project? = project
        override fun isFocusedInWindow(): Boolean = false
        override fun isWindowFocused(): Boolean = false
        override fun requestFocusInWindow() {}
        override fun <T> getUserData(key: Key<T>): T? = data.getUserData(key)
        override fun <T> putUserData(key: Key<T>, value: T?) {
            data.putUserData(key, value)
        }
    }
}
