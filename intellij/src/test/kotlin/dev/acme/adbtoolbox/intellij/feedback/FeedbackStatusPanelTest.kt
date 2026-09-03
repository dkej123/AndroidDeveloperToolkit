package dev.acme.adbtoolbox.intellij.feedback

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.feedback.ProcessIndicator
import dev.acme.adbtoolbox.domain.feedback.StatusState
import com.intellij.ui.components.JBLabel

/** [FeedbackStatusPanel] renders task 013's [StatusState]. Kept as a [BasePlatformTestCase] like every other test in this module. */
class FeedbackStatusPanelTest : BasePlatformTestCase() {

    private fun labels(panel: FeedbackStatusPanel): List<JBLabel> =
        panel.components.filterIsInstance<JBLabel>()

    fun `test an idle status with no message renders empty labels`() {
        val panel = FeedbackStatusPanel()

        panel.update(StatusState())

        labels(panel).forEach { assertEquals("", it.text) }
    }

    fun `test a status message is rendered`() {
        val panel = FeedbackStatusPanel()

        panel.update(StatusState(message = "Refreshed"))

        assertTrue(labels(panel).any { it.text == "Refreshed" })
    }

    fun `test an in-progress process indicator's label is rendered`() {
        val panel = FeedbackStatusPanel()

        panel.update(StatusState(process = ProcessIndicator.InProgress("scrcpy")))

        assertTrue(labels(panel).any { it.text == "scrcpy" })
    }

    fun `test clearing the process indicator clears its label`() {
        val panel = FeedbackStatusPanel()
        panel.update(StatusState(process = ProcessIndicator.InProgress("scrcpy")))

        panel.update(StatusState(process = ProcessIndicator.Idle))

        labels(panel).forEach { assertFalse(it.text == "scrcpy") }
    }
}
