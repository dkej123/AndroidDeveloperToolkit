package dev.acme.adbtoolbox.intellij.feedback

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.domain.feedback.ProcessIndicator
import dev.acme.adbtoolbox.domain.feedback.StatusState
import java.awt.BorderLayout

/**
 * The neutral status-bar rendering of [StatusState] (task 013, `design/README.md` §8): a
 * process-chip label and a last-command-message label, mounted into
 * [dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel.feedbackSlot]. Purely structural — no
 * color, icon, or dimension is set here; that is task 043+'s concern.
 */
class FeedbackStatusPanel : JBPanel<FeedbackStatusPanel>(BorderLayout()) {

    private val processLabel = JBLabel("")
    private val messageLabel = JBLabel("")

    init {
        add(processLabel, BorderLayout.WEST)
        add(messageLabel, BorderLayout.CENTER)
    }

    fun update(status: StatusState) {
        processLabel.text = when (val process = status.process) {
            ProcessIndicator.Idle -> ""
            is ProcessIndicator.InProgress -> process.label
        }
        messageLabel.text = status.message.orEmpty()
    }
}
