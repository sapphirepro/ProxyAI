package ee.carlrobert.codegpt.toolwindow.agent.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

data class DiffAccordionFileLink(
    val text: String,
    val tooltip: String? = null,
    val enabled: Boolean = true,
    val action: () -> Unit
)

data class DiffAccordionBadge(
    val text: String,
    val color: JBColor,
    val tooltip: String? = null
)

data class DiffAccordionAction(
    val label: String,
    val action: (Component) -> Unit
)

data class DiffAccordionModel(
    val icon: Icon?,
    val prefixText: String? = null,
    val titleText: String? = null,
    val subtitleText: String? = null,
    val fileLink: DiffAccordionFileLink? = null,
    val tooltip: String? = null,
    val badges: List<DiffAccordionBadge>,
    val bodyFactory: () -> JComponent,
    val actions: List<DiffAccordionAction> = emptyList()
)

class DiffPreviewAccordionPanel(
    private val model: DiffAccordionModel,
    private var expanded: Boolean,
    private val onExpandedChange: (Boolean) -> Unit
) : JBPanel<DiffPreviewAccordionPanel>() {

    private var bodyComponent: JComponent? = null

    init {
        layout = BorderLayout()
        isOpaque = false
        rebuild()
    }

    private fun rebuild() {
        removeAll()

        val container = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            add(createHeader().apply {
                alignmentX = LEFT_ALIGNMENT
            })
            if (expanded) {
                add(createBody().apply {
                    alignmentX = LEFT_ALIGNMENT
                })
            }
        }

        add(container, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun createHeader(): JComponent {
        val header = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            toolTipText = model.tooltip
        }

        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }

        val titleRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }

        titleRow.add(JBLabel(model.icon).apply {
            toolTipText = model.tooltip
            border = JBUI.Borders.emptyRight(4)
        })
        model.prefixText
            ?.takeIf { it.isNotBlank() }
            ?.let { prefix ->
                titleRow.add(JBLabel(prefix).withFont(JBUI.Fonts.label()).apply {
                    foreground = JBUI.CurrentTheme.Label.foreground()
                    toolTipText = model.tooltip
                })
                titleRow.add(JBLabel(" ").withFont(JBUI.Fonts.label()))
            }
        model.titleText
            ?.takeIf { it.isNotBlank() }
            ?.let { title ->
                titleRow.add(JBLabel(title).withFont(JBUI.Fonts.label()).apply {
                    foreground = JBUI.CurrentTheme.Label.foreground()
                    toolTipText = model.tooltip
                })
                if (model.fileLink != null) {
                    titleRow.add(JBLabel(" ").withFont(JBUI.Fonts.label()))
                }
            }
        model.fileLink?.let { fileLink ->
            titleRow.add(ActionLink(fileLink.text) { fileLink.action() }.apply {
                toolTipText = fileLink.tooltip
                isEnabled = fileLink.enabled
                setExternalLinkIcon()
            })
        }

        model.badges.forEach { badge ->
            titleRow.add(
                JBLabel(badge.text).withFont(JBUI.Fonts.label()).apply {
                    foreground = badge.color
                    toolTipText = badge.tooltip
                    border = JBUI.Borders.emptyLeft(10)
                }
            )
        }

        left.add(titleRow)
        model.subtitleText
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { subtitle ->
                left.add(JBLabel(subtitle).withFont(JBUI.Fonts.smallFont()).apply {
                    foreground = Gray.x88
                    toolTipText = model.tooltip
                    border = JBUI.Borders.emptyLeft(if (model.icon != null) 20 else 0)
                    alignmentX = LEFT_ALIGNMENT
                })
            }

        val chevron =
            JBLabel(if (expanded) AllIcons.General.ArrowUp else AllIcons.General.ArrowDown).apply {
                foreground = Gray.x88
            }

        header.add(left, BorderLayout.CENTER)
        header.add(chevron, BorderLayout.EAST)
        installToggleHandler(header)
        return header
    }

    private fun createBody(): JComponent {
        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(SEPARATOR_COLOR, 1, 0, 0, 0),
                JBUI.Borders.emptyTop(6)
            )

            add(createBodyContent())
            if (model.actions.isNotEmpty()) {
                add(createFooterActions())
            }
            add(createCollapseIndicator())
        }
    }

    private fun createBodyContent(): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 2, 0)
            add(
                bodyComponent ?: model.bodyFactory().also { bodyComponent = it },
                BorderLayout.CENTER
            )
        }
    }

    private fun createFooterActions(): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 0, 0)

            val actionsPanel = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
            }

            model.actions.forEachIndexed { index, action ->
                if (index > 0) {
                    actionsPanel.add(
                        JBLabel("  ·  ").withFont(JBUI.Fonts.smallFont()).apply {
                            foreground = Gray.x88
                        }
                    )
                }
                actionsPanel.add(ActionLink(action.label) { action.action(this) }.apply {
                    font = JBUI.Fonts.smallFont()
                })
            }

            add(actionsPanel, BorderLayout.EAST)
        }
    }

    private fun createCollapseIndicator(): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 2, 0)

            val indicator = JBLabel(AllIcons.General.ArrowDown).apply {
                foreground = Gray.x88
                horizontalAlignment = SwingConstants.CENTER
            }

            add(indicator, BorderLayout.CENTER)
            installToggleHandler(this)
        }
    }

    private fun installToggleHandler(component: Component) {
        if (component is ActionLink) {
            return
        }

        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    toggle()
                }
            }
        })

        if (component is Container) {
            component.components.forEach(::installToggleHandler)
        }
    }

    private fun toggle() {
        expanded = !expanded
        onExpandedChange(expanded)
        rebuild()
    }

    companion object {
        private val SEPARATOR_COLOR = JBColor(Color(0xD9DDE3), Color(0x4B4F52))
    }
}
