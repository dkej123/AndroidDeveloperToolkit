package io.github.dkej123.devicecockpit.intellij.feedback

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackMessage
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackSeverity
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton

/**
 * The bounded toast stack (task 013, `design/README.md`'s "max 3 stacked" toast model), with task
 * 043's supplied visual treatment applied: `panel` background, a 2px severity-colored left border
 * ([FeedbackSeverity.Success] green with a "✓" glyph, [FeedbackSeverity.Error] red with a "✕"
 * glyph — the two shapes `design/README.md`'s Interactions section names explicitly; [FeedbackSeverity.Warning]
 * gets the amber border and [FeedbackSeverity.Info] the neutral `borderStrong` one, both with no
 * glyph, since the supplied design only specifies copy/treatment for success and error toasts), and
 * the accent fix-action button next to a plain dismiss button. Mounted as one overlay in
 * [io.github.dkej123.devicecockpit.intellij.host.AdbToolboxHostPanel.overlays]; anchoring (bottom-left, 8px
 * inset above the status bar) is [io.github.dkej123.devicecockpit.intellij.feedback.FeedbackOverlayCoordinator]'s
 * concern, not this panel's own layout.
 *
 * The queue bound and ordering are [io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel]'s
 * concern; this panel only renders whatever list it is given.
 */
class ToastStackPanel(
    private val onAction: (id: String) -> Unit,
    private val onDismiss: (id: String) -> Unit,
) : JBPanel<ToastStackPanel>() {

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    /** The toast ids currently rendered as rows, in display order — test/verification seam. */
    val renderedIds: List<String> get() = components.filterIsInstance<ToastRow>().map { it.id }

    fun update(toasts: List<FeedbackMessage>) {
        removeAll()
        toasts.forEach { toast -> add(ToastRow(toast, onAction, onDismiss)) }
        revalidate()
        repaint()
    }

    private class ToastRow(
        message: FeedbackMessage,
        onAction: (id: String) -> Unit,
        onDismiss: (id: String) -> Unit,
    ) : JBPanel<ToastRow>(BorderLayout()) {
        val id: String = message.id

        init {
            isOpaque = true
            background = AdbToolboxTheme.Colors.panel
            val (borderColor, glyph) = severityTreatment(message.severity)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 2, 0, 0, borderColor),
                JBUI.Borders.empty(6, 8),
            )

            val textLabel = JBLabel(if (glyph != null) "$glyph  ${message.text}" else message.text).apply {
                foreground = AdbToolboxTheme.Colors.text
                font = AdbToolboxTheme.Typography.body
            }
            add(textLabel, BorderLayout.CENTER)

            val actionsRow = JBPanel<Nothing>(FlowLayout(FlowLayout.TRAILING, AdbToolboxTheme.Spacing.s2, 0)).apply {
                isOpaque = false
            }
            message.action?.let { action ->
                actionsRow.add(
                    JButton(action.label).apply {
                        foreground = AdbToolboxTheme.Colors.accent
                        isContentAreaFilled = false
                        addActionListener { onAction(message.id) }
                    },
                )
            }
            actionsRow.add(JButton("Dismiss").apply { addActionListener { onDismiss(message.id) } })
            add(actionsRow, BorderLayout.EAST)
        }

        private fun severityTreatment(severity: FeedbackSeverity): Pair<Color, String?> = when (severity) {
            FeedbackSeverity.Success -> AdbToolboxTheme.Colors.green to "✓"
            FeedbackSeverity.Error -> AdbToolboxTheme.Colors.red to "✕"
            FeedbackSeverity.Warning -> AdbToolboxTheme.Colors.amber to null
            FeedbackSeverity.Info -> AdbToolboxTheme.Colors.borderStrong to null
        }
    }
}
