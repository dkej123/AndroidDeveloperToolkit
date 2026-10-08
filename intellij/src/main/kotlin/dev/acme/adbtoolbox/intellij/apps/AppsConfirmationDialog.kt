package dev.acme.adbtoolbox.intellij.apps

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * The plugin's only two modals (`design/README.md` §4): 300px max, `panel` background, title
 * 12.5/700, body 11/`textDim`, actions right-aligned. Cancel is always the focused/default action
 * and Escape still maps to it (both inherited, unmodified, from [DialogWrapper]'s own default
 * handling — only [okAction]'s default flag is explicitly cleared below, and [cancelAction]'s is
 * explicitly set) — the red-filled destructive action is never the default, matching
 * [ClearDataConfirmationPresenter] and [UninstallConfirmationPresenter]'s existing
 * Cancel-first/never-default contract ([confirmationForDialogResult]/
 * [uninstallConfirmationForDialogResult] already enforce that mapping independently of which widget
 * shows the dialog).
 */
internal class AppsConfirmationDialog(
    project: Project,
    dialogTitle: String,
    private val bodyText: String,
    destructiveLabel: String,
    /** "Requested by Claude Code over MCP…" when an agent asked for it (design §11), teal under the body. */
    private val agentNote: String? = null,
) : DialogWrapper(project, false) {

    init {
        title = dialogTitle
        setOKButtonText(destructiveLabel)
        isResizable = false
        okAction.putValue(DEFAULT_ACTION, null)
        cancelAction.putValue(DEFAULT_ACTION, true)
        init()
        getButton(okAction)?.let(::styleDestructiveButton)
    }

    override fun createCenterPanel(): JComponent {
        val titleLabel = JBLabel(title).apply {
            font = AdbToolboxTheme.Typography.sectionTitle
            foreground = AdbToolboxTheme.Colors.text
        }
        val bodyWidth = JBUIScale.scale(272)
        val bodyLabel = JBLabel("<html><body style='width:${bodyWidth}px'>$bodyText</body></html>").apply {
            font = AdbToolboxTheme.Typography.body
            foreground = AdbToolboxTheme.Colors.textDim
            verticalAlignment = SwingConstants.TOP
            border = EmptyBorder(JBUIScale.scale(4), 0, 0, 0)
        }
        return JPanel(BorderLayout()).apply {
            background = AdbToolboxTheme.Colors.panel
            border = EmptyBorder(JBUIScale.scale(14), JBUIScale.scale(14), JBUIScale.scale(14), JBUIScale.scale(14))
            preferredSize = Dimension(JBUIScale.scale(300), preferredSize.height)
            add(titleLabel, BorderLayout.NORTH)
            add(bodyLabel, BorderLayout.CENTER)
            agentNote?.let { note ->
                add(JBLabel("<html><body style='width:${bodyWidth}px'>$note</body></html>").apply {
                    font = AdbToolboxTheme.Typography.caption.deriveFont(JBUIScale.scale(10.5f))
                    foreground = AdbToolboxTheme.Colors.brand
                    border = EmptyBorder(JBUIScale.scale(6), 0, 0, 0)
                }, BorderLayout.SOUTH)
            }
        }
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction, okAction)

    override fun getPreferredFocusedComponent(): JComponent? = getButton(cancelAction)
}

/**
 * The destructive action's red-filled treatment — pulled out so it is unit-testable on a plain
 * [javax.swing.JButton]. The colors go through the IDE button UI's own client properties so it
 * paints its usual rounded shape in red; making the button opaque with a red background instead
 * paints a square block behind that shape.
 */
internal fun styleDestructiveButton(button: javax.swing.JButton) {
    button.isOpaque = false
    button.putClientProperty("JButton.backgroundColor", AdbToolboxTheme.Colors.red)
    button.putClientProperty("JButton.textColor", Color.WHITE)
    button.foreground = Color.WHITE
}
