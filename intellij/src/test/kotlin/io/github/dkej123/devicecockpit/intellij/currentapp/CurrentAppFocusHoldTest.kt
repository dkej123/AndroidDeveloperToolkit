package io.github.dkej123.devicecockpit.intellij.currentapp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.FocusEvent
import javax.swing.JButton
import javax.swing.JPanel

class CurrentAppFocusHoldTest : BasePlatformTestCase() {
    private val section = JPanel()
    private val inside = JButton("Refresh").also { section.add(it) }
    private val outside = JButton("Elsewhere")

    private fun gained(component: JButton, cause: FocusEvent.Cause) =
        FocusEvent(component, FocusEvent.FOCUS_GAINED, false, null, cause)

    fun `test tabbing into the section holds a change`() {
        assertTrue(holdsFocus(gained(inside, FocusEvent.Cause.TRAVERSAL_FORWARD), section))
        assertTrue(holdsFocus(gained(inside, FocusEvent.Cause.TRAVERSAL_BACKWARD), section))
    }

    fun `test focus a click leaves in the section does not hold later changes`() {
        assertFalse(holdsFocus(gained(inside, FocusEvent.Cause.MOUSE_EVENT), section))
    }

    fun `test focus restored after a confirmation dialog does not hold`() {
        assertFalse(holdsFocus(gained(inside, FocusEvent.Cause.ACTIVATION), section))
    }

    fun `test keyboard focus outside the section does not hold`() {
        assertFalse(holdsFocus(gained(outside, FocusEvent.Cause.TRAVERSAL_FORWARD), section))
    }
}
