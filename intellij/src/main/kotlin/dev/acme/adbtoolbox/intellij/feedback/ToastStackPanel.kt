package dev.acme.adbtoolbox.intellij.feedback

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import java.awt.GridLayout
import javax.swing.BoxLayout
import javax.swing.JButton

/**
 * The neutral bounded toast stack (task 013, `design/README.md`'s "max 3 stacked" toast model):
 * one row per active [FeedbackMessage], each with its text, an optional single fix-action button,
 * and a dismiss button — mounted as one overlay in
 * [dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel.overlays]. The queue bound and ordering
 * are [dev.acme.adbtoolbox.application.feedback.FeedbackViewModel]'s concern; this panel only
 * renders whatever list it is given. Purely structural — no anchoring, color, icon, shadow, or
 * dimension is set here; that is task 043+'s concern.
 */
class ToastStackPanel(
    private val onAction: (id: String) -> Unit,
    private val onDismiss: (id: String) -> Unit,
) : JBPanel<ToastStackPanel>() {

    init {
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
    ) : JBPanel<ToastRow>(GridLayout(1, 0)) {
        val id: String = message.id

        init {
            add(JBLabel(message.text))
            message.action?.let { action ->
                add(JButton(action.label).apply { addActionListener { onAction(message.id) } })
            }
            add(JButton("Dismiss").apply { addActionListener { onDismiss(message.id) } })
        }
    }
}
