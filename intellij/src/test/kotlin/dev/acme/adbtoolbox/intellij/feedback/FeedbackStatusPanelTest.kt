package dev.acme.adbtoolbox.intellij.feedback

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import dev.acme.adbtoolbox.domain.feedback.ProcessIndicator
import dev.acme.adbtoolbox.domain.feedback.StatusState
import java.awt.Component
import java.awt.Container

/**
 * [FeedbackStatusPanel] renders task 013's [StatusState] with task 043's supplied visual treatment
 * (`design/README.md` §8). Kept as a [BasePlatformTestCase] like every other test in this module.
 */
class FeedbackStatusPanelTest : BasePlatformTestCase() {

    private fun labels(panel: FeedbackStatusPanel): List<JBLabel> = allDescendants(panel).filterIsInstance<JBLabel>()

    private fun allDescendants(container: Container): List<Component> =
        container.components.flatMap { child ->
            if (child is Container) listOf(child) + allDescendants(child) else listOf(child)
        }

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

    fun `test a recording process indicator is rendered with its label`() {
        val panel = FeedbackStatusPanel()

        panel.update(StatusState(process = ProcessIndicator.InProgress("REC 00:42")))

        assertTrue(labels(panel).any { it.text == "REC 00:42" })
    }

    fun `test zero overrides hides the override chip`() {
        val panel = FeedbackStatusPanel()

        panel.updateOverrideCount(0)

        assertFalse(panel.overrideChipVisible)
    }

    fun `test a positive override count shows the chip with the expected copy`() {
        val panel = FeedbackStatusPanel()

        panel.updateOverrideCount(3)

        assertTrue(panel.overrideChipVisible)
        assertEquals("3 overrides · reset all", panel.overrideChipText)
    }

    fun `test a single override uses the singular copy`() {
        val panel = FeedbackStatusPanel()

        panel.updateOverrideCount(1)

        assertEquals("1 override · reset all", panel.overrideChipText)
    }

    fun `test clicking the override chip invokes onResetOverrides`() {
        var reset = false
        val panel = FeedbackStatusPanel(onResetOverrides = { reset = true })
        panel.updateOverrideCount(2)

        panel.overrideChipComponentForTest.doClick()

        assertTrue(reset)
    }

    // ---- Task 050: the override chip is the status bar's only interactive control ----

    fun `test the override chip is a focusable button reachable by keyboard, not a mouse-only label`() {
        val panel = FeedbackStatusPanel()
        panel.updateOverrideCount(1)

        assertTrue(panel.overrideChipComponentForTest.isFocusable)
    }

    fun `test the override chip exposes an accessible name matching its rendered copy`() {
        val panel = FeedbackStatusPanel()

        panel.updateOverrideCount(3)

        assertEquals("3 overrides · reset all", panel.overrideChipComponentForTest.getAccessibleContext().accessibleName)
    }

    // ---- Task 051: a long last-command message must never clip the required "reset all" action ----

    fun `test an overlong message never overlaps the override chip's bounds at a narrow width`() {
        val panel = FeedbackStatusPanel()
        panel.updateOverrideCount(3)
        panel.update(
            StatusState(
                message = "A very long last-command message that would overflow a narrow docked tool window width",
            ),
        )

        panel.setSize(260, 22)
        panel.doLayout()
        val chip = panel.overrideChipComponentForTest
        chip.parent.doLayout()

        // The message is the row's only flexible child: it is clipped to the remaining space and
        // never extends into the required "reset all" action.
        val message = chip.parent.components.first { it is javax.swing.JLabel && it.isVisible && it !== chip }
        assertTrue(message.x + message.width <= chip.x)
        assertTrue(javax.swing.SwingUtilities.convertPoint(chip.parent, chip.x + chip.width, 0, panel).x <= panel.width)
    }
}
