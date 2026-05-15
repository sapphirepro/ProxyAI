package ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.OpenFileAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.Gray
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.agent.tools.AskUserQuestionTool
import ee.carlrobert.codegpt.agent.tools.IntelliJSearchTool
import ee.carlrobert.codegpt.toolwindow.agent.ui.components.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.*

class ToolCallView(
    private var descriptor: ToolCallDescriptor
) : JBPanel<ToolCallView>() {

    private var diffPreviewExpanded = false
    private var searchResultsExpanded = false
    private var headerPanel = createHeaderPanel()
    private val streamingPanel = ToolCallStreamingPanel()

    init {
        layout = BorderLayout()
        isOpaque = false

        border = JBUI.Borders.empty()

        add(headerPanel, BorderLayout.NORTH)
        add(streamingPanel, BorderLayout.CENTER)
    }

    fun complete(success: Boolean, result: Any?) {
        headerPanel.updateCompletionStatus(result)
        when (result) {
            is AskUserQuestionTool.Result.Success -> {
                val compactLine = result.answers.entries.joinToString(" · ") { (k, v) -> "$k: $v" }
                streamingPanel.showCompactInfo(listOf(compactLine))
            }

            else -> streamingPanel.onCompletion()
        }
    }

    fun appendStreamingLine(text: String, isError: Boolean) {
        if (descriptor.supportsStreaming) {
            streamingPanel.appendLine(text, isError)
        }
    }

    fun getDescriptor(): ToolCallDescriptor = descriptor

    fun refreshDescriptor(newDescriptor: ToolCallDescriptor) {
        this.descriptor = newDescriptor
        remove(headerPanel)
        headerPanel = createHeaderPanel()
        add(headerPanel, BorderLayout.NORTH)
        revalidate()
        repaint()
    }

    private fun createHeaderPanel(): ToolCallHeaderPanel {
        return ToolCallHeaderPanel(
            descriptor = descriptor,
            isDiffPreviewExpanded = diffPreviewExpanded,
            isSearchResultsExpanded = searchResultsExpanded,
            onDiffPreviewExpandedChange = {
                diffPreviewExpanded = it
                refreshDescriptor(descriptor)
            },
            onSearchResultsExpandedChange = {
                searchResultsExpanded = it
                refreshDescriptor(descriptor)
            }
        )
    }
}

private class ToolCallHeaderPanel(
    private val descriptor: ToolCallDescriptor,
    private val isDiffPreviewExpanded: Boolean,
    private val isSearchResultsExpanded: Boolean,
    private val onDiffPreviewExpandedChange: (Boolean) -> Unit,
    private val onSearchResultsExpandedChange: (Boolean) -> Unit
) : JBPanel<ToolCallHeaderPanel>() {

    private val leftRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
    private val contentPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private var fileLink: ActionLink? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false

        buildDiffAccordion()?.let(::add) ?: run {
            buildHeaderContent()
            buildHeaderToggle()?.let { leftRow.add(it) }
            installSearchHeaderToggle(leftRow)
            contentPanel.add(leftRow)
            buildSecondaryLine()?.let { contentPanel.add(it) }
            add(contentPanel)
        }
    }

    private fun buildDiffAccordion(): JComponent? {
        val preview = descriptor.diffPreview ?: return null
        val fileLink = descriptor.fileLink ?: return null
        if (descriptor.kind != ToolKind.WRITE && descriptor.kind != ToolKind.EDIT) {
            return null
        }

        return DiffPreviewAccordionPanel(
            model = DiffAccordionModel(
                icon = descriptor.icon,
                prefixText = descriptor.titlePrefix.takeIf { it.isNotBlank() },
                titleText = descriptor.titleMain.takeIf { it.isNotBlank() },
                subtitleText = descriptor.subtitleText,
                fileLink = createAccordionFileLink(fileLink),
                tooltip = fileLink.path,
                badges = descriptor.secondaryBadges
                    .filter { it.action == null && isDiffBadge(it.text.trim()) }
                    .map { badge ->
                        DiffAccordionBadge(
                            text = badge.text.trim(),
                            color = badge.color,
                            tooltip = badge.tooltip
                        )
                    },
                bodyFactory = { UnifiedDiffPreviewPanel(getProject(), preview) },
                actions = emptyList()
            ),
            expanded = isDiffPreviewExpanded,
            onExpandedChange = onDiffPreviewExpandedChange
        )
    }

    private fun createAccordionFileLink(fileLink: FileLink): DiffAccordionFileLink {
        return DiffAccordionFileLink(
            text = fileLink.displayName,
            tooltip = if (fileLink.line != null) {
                "${fileLink.path}:${fileLink.line}"
            } else {
                fileLink.path
            },
            enabled = fileLink.enabled
        ) {
            openFileLink(fileLink)
        }
    }

    private fun buildHeaderContent() {
        val prefixLabel = JBLabel(
            if (descriptor.titlePrefix.isNotEmpty()) "${descriptor.titlePrefix} " else "",
            descriptor.icon,
            SwingConstants.LEFT
        ).withFont(JBFont.label()).apply {
            descriptor.prefixColor?.let { color ->
                foreground = color
            }
            if (descriptor.titlePrefix.isEmpty()) {
                border = JBUI.Borders.emptyRight(4)
            }
        }
        leftRow.add(prefixLabel)

        when {
            descriptor.fileLink != null -> addFileLink()
            else -> addRegularContent()
        }

        addSecondaryBadges()

    }

    private fun addFileLink() {
        val fileLink = descriptor.fileLink!!
        if (descriptor.titleMain.isNotBlank()) {
            leftRow.add(JBLabel(descriptor.titleMain).apply {
                if (!descriptor.tooltip.isNullOrBlank()) {
                    toolTipText = descriptor.tooltip
                }
            })
            leftRow.add(mutedLabel("  "))
        }
        val link = ActionLink(fileLink.displayName) {
            openFileLink(fileLink)
        }.apply {
            toolTipText = if (fileLink.line != null) {
                "${fileLink.path}:${fileLink.line}"
            } else {
                fileLink.path
            }
            setExternalLinkIcon()
            isEnabled = fileLink.enabled
        }

        this.fileLink = link
        leftRow.add(link)
    }

    private fun shouldShowFileLinkSummary(summary: String, fileLink: FileLink): Boolean {
        val normalizedSummary = summary.trim()
        if (normalizedSummary.isEmpty()) {
            return false
        }

        val linkDisplay = fileLink.displayName.trim()
        val lineSuffix = fileLink.line?.let { ":L$it" }.orEmpty()
        val pathWithLine = "${fileLink.path}$lineSuffix"
        return normalizedSummary != fileLink.path &&
                normalizedSummary != pathWithLine &&
                normalizedSummary != linkDisplay
    }

    private fun addRegularContent() {
        val content = JBLabel(descriptor.titleMain)
        if (!descriptor.tooltip.isNullOrBlank()) {
            content.toolTipText = descriptor.tooltip
        }
        leftRow.add(content)

        addSearchParametersIfAny()
    }

    private fun buildHeaderToggle(): JComponent? {
        val result = descriptor.result as? IntelliJSearchTool.Result ?: return null
        if (descriptor.kind != ToolKind.SEARCH || result.matches.size <= 1) {
            return null
        }

        val expanded = isSearchResultsExpanded
        return ActionLink("") {
            onSearchResultsExpandedChange(!expanded)
        }.apply {
            icon = if (expanded) AllIcons.General.ArrowUp else AllIcons.General.ArrowDown
            border = JBUI.Borders.emptyLeft(10)
            toolTipText = if (expanded) "Collapse results" else "Expand results"
        }
    }

    private fun installSearchHeaderToggle(component: Component) {
        val result = descriptor.result as? IntelliJSearchTool.Result ?: return
        if (descriptor.kind != ToolKind.SEARCH || result.matches.size <= 1 || component is ActionLink) {
            return
        }

        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    onSearchResultsExpandedChange(!isSearchResultsExpanded)
                }
            }
        })

        if (component is Container) {
            component.components.forEach(::installSearchHeaderToggle)
        }
    }

    private fun addSecondaryBadges() {
        var prevWasDiff = false
        descriptor.secondaryBadges.forEach { badge ->
            val isDiff = isDiffBadge(badge.text)
            val leftGap = if (isDiff && prevWasDiff) 0 else 4
            if (badge.action != null) {
                leftRow.add(ActionLink("[${badge.text}]") {
                    badge.action.invoke()
                }.apply {
                    font = JBUI.Fonts.smallFont()
                    if (badge.tooltip != null) {
                        toolTipText = badge.tooltip
                    }
                    border = JBUI.Borders.emptyLeft(leftGap)
                })
            } else {
                leftRow.add(JBLabel(badge.text).withFont(JBFont.small()).apply {
                    foreground = badge.color
                    if (badge.tooltip != null) {
                        toolTipText = badge.tooltip
                    }
                    border = JBUI.Borders.compound(
                        JBUI.Borders.emptyLeft(leftGap),
                        JBUI.Borders.empty(1, 6)
                    )
                })
            }
            prevWasDiff = isDiff
        }
    }

    private fun isDiffBadge(text: String): Boolean {
        return text.startsWith("+") || text.startsWith("-") || text.startsWith("~")
    }

    private fun showSearchResultsDialog(content: String) {
        val dialog = JDialog().apply {
            title = "Search Results"
            isModal = true
        }

        val textArea = JTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.smallFont()
        }

        val scrollPane = JScrollPane(textArea).apply {
            preferredSize = JBUI.size(700, 400)
            border =
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        }

        val footerPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JButton("Copy").apply {
                addActionListener {
                    val selection = StringSelection(content)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        selection,
                        null
                    )
                }
            })
            add(JButton("Close").apply {
                addActionListener { dialog.dispose() }
            })
        }

        dialog.contentPane = BorderLayoutPanel().apply {
            add(scrollPane, BorderLayout.CENTER)
            add(footerPanel, BorderLayout.SOUTH)
        }
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    fun updateCompletionStatus(result: Any?) {
        descriptor.fileLink?.let { fileLink ->
            if (!fileLink.enabled && result != null) {
                this.fileLink?.isEnabled = true
            }
        }

        revalidate()
        repaint()
    }

    private fun buildSecondaryLine(): JComponent? {
        val detailText = descriptor.detailText?.trim().takeUnless { it.isNullOrEmpty() }
            ?: descriptor.summary
                ?.trim()
                ?.takeUnless { it.isNullOrEmpty() }
                ?.takeIf { summary ->
                    descriptor.fileLink?.let { shouldShowFileLinkSummary(summary, it) } ?: true
                }
        val inlineActions = buildInlineActions()
        val searchResult = descriptor.result as? IntelliJSearchTool.Result

        if (descriptor.kind == ToolKind.SEARCH && searchResult?.matches?.isNotEmpty() == true) {
            return buildSearchResultsSection(searchResult, inlineActions)
        }

        if (detailText == null && inlineActions.isEmpty()) {
            return null
        }

        return if (descriptor.secondaryLayout == ToolCallSecondaryLayout.STACKED) {
            JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                detailText?.let {
                    add(buildSummaryRow(it, inlineActions = emptyList()))
                }
                if (inlineActions.isNotEmpty()) {
                    add(buildSummaryRow(null, inlineActions))
                }
            }
        } else {
            buildSummaryRow(detailText, inlineActions)
        }
    }

    private fun buildSearchResultsSection(
        result: IntelliJSearchTool.Result,
        inlineActions: List<InlineAction>
    ): JComponent {
        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(buildSearchSummaryRow(result))
            if (isSearchResultsExpanded && result.matches.size > 1) {
                add(buildExpandedSearchResults(result))
            }
            buildSearchFooter(result)?.let(::add)
            if (inlineActions.isNotEmpty()) {
                add(buildSummaryRow(null, inlineActions))
            }
        }
    }

    private fun buildSearchSummaryRow(result: IntelliJSearchTool.Result): JComponent {
        val firstMatch = result.matches.first()

        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 20, 0, 0)

            add(createSearchMatchLink(firstMatch))
        }
    }

    private fun buildExpandedSearchResults(result: IntelliJSearchTool.Result): JComponent {
        val visibleMatches = result.matches.drop(1).take(MAX_VISIBLE_SEARCH_RESULTS)
        val hiddenCount = result.matches.size - 1 - visibleMatches.size

        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            visibleMatches.forEach { match ->
                add(buildSearchMatchRow(match))
            }

            if (hiddenCount > 0) {
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(2, 20, 0, 0)
                    add(ActionLink("Show all ${result.matches.size} results") {
                        showSearchResultsDialog(formatSearchResultsDialogContent(result))
                    }.apply {
                        font = JBUI.Fonts.smallFont()
                        foreground = Gray.x88
                    })
                })
            }
        }
    }

    private fun buildSearchFooter(result: IntelliJSearchTool.Result): JComponent? {
        val remaining = result.matches.size - 1
        if (remaining <= 0) {
            return null
        }

        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 20, 0, 0)
            add(ActionLink(if (isSearchResultsExpanded) "Collapse" else "+$remaining more") {
                onSearchResultsExpandedChange(!isSearchResultsExpanded)
            }.apply {
                font = JBUI.Fonts.smallFont()
            })
        }
    }

    private fun buildSearchMatchRow(match: IntelliJSearchTool.SearchMatch): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 20, 0, 0)

            add(createSearchMatchLink(match))
        }
    }

    private fun createSearchMatchLink(match: IntelliJSearchTool.SearchMatch): ActionLink {
        return ActionLink(formatSearchLocation(match)) {
            openSearchMatch(match)
        }.apply {
            font = JBUI.Fonts.smallFont()
            foreground = Gray.x88
            toolTipText = formatSearchTooltip(match)
            setExternalLinkIcon()
        }
    }

    private fun buildSummaryRow(
        detailText: String?,
        inlineActions: List<InlineAction>
    ): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 20, 0, 0)

            if (detailText != null) {
                add(mutedLabel(detailText))
            }

            inlineActions.forEachIndexed { index, action ->
                if (detailText != null || index > 0) {
                    add(mutedLabel("  ·  "))
                }
                add(ActionLink("[${action.label}]") { action.action() }.apply {
                    font = JBUI.Fonts.smallFont()
                })
            }
        }
    }

    private fun mutedLabel(text: String): JBLabel {
        return JBLabel(text).withFont(JBFont.small()).apply {
            foreground = Gray.x88
        }
    }

    private fun buildInlineActions(): List<InlineAction> {
        val descriptorActions = descriptor.actions.map { action ->
            InlineAction(action.name) { action.action(this@ToolCallHeaderPanel) }
        }

        return descriptorActions
    }

    private data class InlineAction(
        val label: String,
        val action: () -> Unit
    )

    private fun addSearchParametersIfAny() {
        val args = descriptor.args
        if (args is IntelliJSearchTool.Args) {
            val params = buildList {
                if (args.caseSensitive == true) add("case")
                if (args.regex == true) add("regex")
                if (args.wholeWords == true) add("words")
                args.context?.let { c ->
                    if (c.isNotBlank() && !c.equals(
                            "ANY",
                            true
                        )
                    ) add("context: $c")
                }
                args.fileType?.let { ft -> if (ft.isNotBlank()) add("type: $ft") }
                args.outputMode?.let { om ->
                    if (om.isNotBlank() && !om.equals(
                            "content",
                            true
                        )
                    ) add("out: $om")
                }
                args.limit?.let { lim -> add("limit: $lim") }
            }.joinToString(" · ")

            if (params.isNotBlank()) {
                leftRow.add(JBLabel(" ($params)"))
            }
        }
    }

    private fun getProject(): Project? {
        return descriptor.projectId?.let { projectId ->
            ProjectManager.getInstance().openProjects.find { it.locationHash == projectId }
        } ?: ProjectManager.getInstance().openProjects.firstOrNull()
    }

    private fun openFileLink(fileLink: FileLink) {
        val project = getProject() ?: return
        fileLink.action?.invoke(project) ?: openVirtualFile(
            project,
            resolveToolCallVirtualFile(project, fileLink.path) ?: return,
            fileLink.line,
            fileLink.column
        )
    }

    private fun openSearchMatch(match: IntelliJSearchTool.SearchMatch) {
        val project = getProject() ?: return
        val virtualFile = resolveToolCallVirtualFile(project, match.file) ?: return
        openVirtualFile(project, virtualFile, match.line, match.column)
    }

    private fun formatSearchLocation(match: IntelliJSearchTool.SearchMatch): String {
        return toProjectRelativePath(match.file)
    }

    private fun formatSearchTooltip(match: IntelliJSearchTool.SearchMatch): String {
        val columnSuffix = match.column?.let { ":$it" }.orEmpty()
        return if (match.line != null) {
            "${match.file}:${match.line}$columnSuffix"
        } else {
            match.file
        }
    }

    private fun formatSearchResultsDialogContent(result: IntelliJSearchTool.Result): String {
        return buildString {
            appendLine("Pattern: ${result.pattern}")
            appendLine("Scope: ${result.scope}")
            appendLine("Total matches: ${result.totalMatches}")
            appendLine()
            result.matches.forEachIndexed { index, match ->
                appendLine("${index + 1}. ${formatSearchLocation(match)}")
                match.context
                    ?.condenseWhitespace()
                    ?.takeIf { it.isNotBlank() }
                    ?.take(MAX_SEARCH_SUMMARY_LENGTH)
                    ?.let { appendLine("   $it") }
                appendLine()
            }
        }.trimEnd()
    }

    private fun toProjectRelativePath(path: String): String {
        val projectBasePath = getProject()?.basePath
        val normalizedPath = path.replace("\\", "/")
        val normalizedBase = projectBasePath?.replace("\\", "/")?.trimEnd('/')
        return if (!normalizedBase.isNullOrBlank() && normalizedPath.startsWith("$normalizedBase/")) {
            normalizedPath.removePrefix("$normalizedBase/")
        } else {
            normalizedPath
        }
    }

    companion object {
        private const val MAX_VISIBLE_SEARCH_RESULTS = 7
        private const val MAX_SEARCH_SUMMARY_LENGTH = 140
    }
}

internal fun resolveToolCallVirtualFile(project: Project, rawPath: String): VirtualFile? {
    val fs = LocalFileSystem.getInstance()
    val normalizedRawPath = rawPath.replace("\\", "/")

    fs.findFileByPath(normalizedRawPath)?.let { return it }

    val resolvedPath = resolveToolCallFileSystemPath(project.basePath, rawPath)
    if (resolvedPath != normalizedRawPath) {
        fs.findFileByPath(resolvedPath)?.let { return it }
    }

    return null
}

internal fun resolveToolCallFileSystemPath(projectBasePath: String?, rawPath: String): String {
    val normalizedRawPath = rawPath.replace("\\", "/")
    if (normalizedRawPath.isBlank()) {
        return normalizedRawPath
    }

    return try {
        val path = Path.of(rawPath)
        when {
            path.isAbsolute -> path.normalize().toString().replace("\\", "/")
            !projectBasePath.isNullOrBlank() ->
                Path.of(projectBasePath, rawPath).normalize().toString().replace("\\", "/")

            else -> normalizedRawPath
        }
    } catch (_: Exception) {
        normalizedRawPath
    }
}

private fun openVirtualFile(
    project: Project,
    virtualFile: VirtualFile,
    line: Int?,
    column: Int?
) {
    if (line != null) {
        val descriptor = OpenFileDescriptor(
            project,
            virtualFile,
            line - 1,
            column ?: 0
        )
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    } else {
        OpenFileAction.openFile(virtualFile, project)
    }
}

private fun String.condenseWhitespace(): String {
    return replace("\r", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
}

/**
 * Handles streaming output display.
 */
private class ToolCallStreamingPanel : JBPanel<ToolCallStreamingPanel>() {

    private val contentPanel = JBPanel<JBPanel<*>>().apply {
        isOpaque = false
        layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        border = JBUI.Borders.empty(2, 20, 0, 0)
        isVisible = false
    }

    private val streamingLabel = JBLabel("")
    private val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(12)
        add(ActionLink("Show details") { showDetailsDialog() }.apply {
            font = JBUI.Fonts.smallFont()
        })
        add(ActionLink("Copy") { copyToClipboard() }.apply {
            font = JBUI.Fonts.smallFont()
        })
        isVisible = false
    }

    private val streamingTail: ArrayDeque<Pair<String, Boolean>> = ArrayDeque()
    private val streamingAllLines: ArrayDeque<Pair<String, Boolean>> = ArrayDeque()
    private var updateScheduled = false

    companion object {
        private const val MAX_TAIL_LINES = 3
        private const val MAX_DETAIL_LINES = 500
    }

    init {
        layout = BorderLayout()
        isOpaque = false

        add(contentPanel, BorderLayout.CENTER)
        add(actionsPanel, BorderLayout.SOUTH)
    }

    fun showCompactInfo(lines: List<String>) {
        if (lines.isEmpty()) {
            contentPanel.isVisible = false
            actionsPanel.isVisible = false
            return
        }
        val html = buildString {
            append("<html><body style='color:#808080'>")
            lines.forEachIndexed { i, line ->
                val safe = sanitizeHtml(line.take(300))
                append(safe)
                if (i < lines.size - 1) append("<br>")
            }
            append("</body></html>")
        }
        streamingLabel.font = JBUI.Fonts.smallFont()
        streamingLabel.text = html
        contentPanel.removeAll()
        contentPanel.add(streamingLabel)
        contentPanel.isVisible = true
        actionsPanel.isVisible = false
        revalidate()
        repaint()
    }

    fun appendLine(text: String, isError: Boolean) {
        if (text.isBlank()) return

        streamingTail.addLast(text to isError)
        while (streamingTail.size > MAX_TAIL_LINES) {
            streamingTail.removeFirst()
        }

        streamingAllLines.addLast(text to isError)
        while (streamingAllLines.size > MAX_DETAIL_LINES) {
            streamingAllLines.removeFirst()
        }

        scheduleDisplayUpdate()
    }

    fun onCompletion() {
        contentPanel.isVisible = streamingTail.isNotEmpty()
        scheduleDisplayUpdate()
    }

    private fun updateDisplay() {
        if (streamingTail.isEmpty()) {
            contentPanel.removeAll()
            contentPanel.isVisible = false
            actionsPanel.isVisible = false
            revalidate()
            repaint()
            return
        }

        val html = buildTailHtml()
        val font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.smallFont().size))

        streamingLabel.font = font
        streamingLabel.text = html

        contentPanel.removeAll()
        contentPanel.add(streamingLabel)
        contentPanel.isVisible = true
        actionsPanel.isVisible = true

        revalidate()
        repaint()
    }

    private fun scheduleDisplayUpdate() {
        if (updateScheduled) {
            return
        }
        updateScheduled = true
        SwingUtilities.invokeLater {
            updateScheduled = false
            updateDisplay()
        }
    }

    private fun buildTailHtml(): String {
        return buildString {
            append("<html><body style='width: 400px'>")
            streamingTail.forEachIndexed { idx, (line, isError) ->
                val safeText = sanitizeHtml(line.take(200))
                val color = if (isError) "#ff5555" else "#808080"
                append("<span style='color:$color'>$safeText</span>")
                if (idx < streamingTail.size - 1) append("<br>")
            }
            append("</body></html>")
        }
    }

    private fun sanitizeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun copyToClipboard() {
        val content = buildString {
            streamingAllLines.forEachIndexed { i, (line, _) ->
                append(line)
                if (i < streamingAllLines.size - 1) append('\n')
            }
        }
        val selection = StringSelection(content)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }

    private fun showDetailsDialog() {
        val dialog = JDialog().apply {
            title = "Streaming Output"
            isModal = true
        }

        val content = buildString {
            streamingAllLines.forEachIndexed { i, (line, _) ->
                append(line)
                if (i < streamingAllLines.size - 1) append('\n')
            }
        }

        val textArea = JTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.smallFont()
        }

        val scrollPane = JScrollPane(textArea).apply {
            preferredSize = JBUI.size(700, 400)
            border =
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        }

        val footerPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JButton("Copy").apply {
                addActionListener { copyToClipboard() }
            })
            add(JButton("Close").apply {
                addActionListener { dialog.dispose() }
            })
        }

        dialog.contentPane = BorderLayoutPanel().apply {
            add(scrollPane, BorderLayout.CENTER)
            add(footerPanel, BorderLayout.SOUTH)
        }
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }
}
