package io.github.dkej123.devicecockpit.intellij.feedback

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackMessage
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackSeverity
import io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme
import com.intellij.ui.scale.JBUIScale
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButton
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButtonStyle
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BoxLayout

/**
 * The bounded toast stack (task 013, `design/README.md`'s "max 3 stacked" toast model), with task
 * 043's supplied visual treatment applied: rounded `panel` card, a 3px severity-colored left bar
 * ([FeedbackSeverity.Success] green with a "✓" glyph, [FeedbackSeverity.Error] red with a "✕"
 * glyph — the two shapes `design/README.md`'s Interactions section names explicitly; [FeedbackSeverity.Warning]
 * gets the amber border and [FeedbackSeverity.Info] the neutral `borderStrong` one, both with no
 * glyph, since the supplied design only specifies copy/treatment for success and error toasts), and
 * the accent fix-action button next to a plain dismiss button. Mounted as one overlay in
 * [io.github.dkej123.devicecockpit.intellij.host.DeviceCockpitHostPanel.overlays]; anchoring (bottom-left, 8px
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

    /**
     * The prototype's `toastStyle`: radius-5 `panel` card, 1px `border` (`redBorder` for errors), a 3px
     * severity bar on the left, glyph 11/700 in the severity colour, text 11px, actions as 11px links.
     * Painted on a transparent row so the tool window shows through outside the rounded corners.
     */
    private class ToastRow(
        message: FeedbackMessage,
        onAction: (id: String) -> Unit,
        onDismiss: (id: String) -> Unit,
    ) : JBPanel<ToastRow>(BorderLayout(JBUI.scale(7), 0)) {
        val id: String = message.id
        private val severityColor: Color
        private val outline: Color

        init {
            isOpaque = false
            val (color, glyph) = severityTreatment(message.severity)
            severityColor = color
            outline = if (message.severity == FeedbackSeverity.Error) DeviceCockpitTheme.Colors.redBorder else DeviceCockpitTheme.Colors.border
            border = JBUI.Borders.empty(6, 9 + 3, 6, 9)

            if (glyph != null) {
                add(JBLabel(glyph).apply {
                    foreground = color
                    font = DeviceCockpitTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(11f))
                }, BorderLayout.WEST)
            }
            add(JBLabel(message.text).apply {
                foreground = DeviceCockpitTheme.Colors.text
                font = DeviceCockpitTheme.Typography.body.deriveFont(JBUIScale.scale(11f))
            }, BorderLayout.CENTER)

            val actionsRow = JBPanel<Nothing>(FlowLayout(FlowLayout.TRAILING, DeviceCockpitTheme.Spacing.s2, 0)).apply {
                isOpaque = false
            }
            message.action?.let { action ->
                actionsRow.add(DesignButton(action.label, DesignButtonStyle.LINK).apply {
                    font = DeviceCockpitTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(11f))
                    addActionListener { onAction(message.id) }
                })
            }
            actionsRow.add(DesignButton("Dismiss", DesignButtonStyle.LINK).apply {
                foreground = DeviceCockpitTheme.Colors.textDim
                addActionListener { onDismiss(message.id) }
            })
            add(actionsRow, BorderLayout.EAST)
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUIScale.scale(10f)
                val stroke = JBUIScale.scale(1f)
                val card = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc)
                g.color = DeviceCockpitTheme.Colors.panel
                g.fill(card)
                g.color = outline
                g.stroke = BasicStroke(stroke)
                g.draw(RoundRectangle2D.Float(stroke / 2, stroke / 2, width - stroke, height - stroke, arc, arc))
                g.clip(card)
                g.color = severityColor
                g.fillRect(0, 0, JBUI.scale(3), height)
            } finally {
                g.dispose()
            }
        }

        private fun severityTreatment(severity: FeedbackSeverity): Pair<Color, String?> = when (severity) {
            FeedbackSeverity.Success -> DeviceCockpitTheme.Colors.green to "✓"
            FeedbackSeverity.Error -> DeviceCockpitTheme.Colors.red to "✕"
            FeedbackSeverity.Warning -> DeviceCockpitTheme.Colors.amber to null
            FeedbackSeverity.Info -> DeviceCockpitTheme.Colors.borderStrong to null
        }
    }
}
