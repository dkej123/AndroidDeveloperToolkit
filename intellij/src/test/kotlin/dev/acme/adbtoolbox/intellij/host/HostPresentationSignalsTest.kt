package dev.acme.adbtoolbox.intellij.host

import com.intellij.ide.ui.LafManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.ComponentEvent
import javax.swing.JPanel

/**
 * [HostPresentationSignals] exposes raw width/theme-change platform inputs (task 010) — no visual
 * decision is made here, only the signal a later presentation task (051) will consume. Fires the
 * registered listeners directly ([HostPresentationSignals.resizeListener] /
 * [HostPresentationSignals.lafListener] are `internal` for exactly this) instead of depending on
 * real AWT event-queue delivery or an actual Look and Feel switch inside this headless sandbox —
 * both are unreliable/unavailable here, per this module's established testing constraints.
 */
class HostPresentationSignalsTest : BasePlatformTestCase() {

    fun `test resize listener updates the width flow when fired`() {
        val component = JPanel().apply { setSize(640, 480) }
        val signals = HostPresentationSignals(component)
        signals.attach()

        signals.resizeListener.componentResized(ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED))

        assertEquals(640, signals.width.value)
    }

    fun `test detach removes the resize listener from the host component`() {
        val component = JPanel()
        val signals = HostPresentationSignals(component)
        signals.attach()
        assertTrue(component.componentListeners.contains(signals.resizeListener))

        signals.detach()

        assertFalse(component.componentListeners.contains(signals.resizeListener))
    }

    fun `test theme listener publishes a theme change when fired`() {
        val component = JPanel()
        val signals = HostPresentationSignals(component)
        signals.attach()

        signals.lafListener.lookAndFeelChanged(LafManager.getInstance())

        assertEquals(listOf(Unit), signals.themeChanges.replayCache)
    }

    fun `test detach tears down the theme listener connection`() {
        val component = JPanel()
        val signals = HostPresentationSignals(component)
        signals.attach()
        assertTrue(signals.isThemeListenerAttached)

        signals.detach()

        assertFalse(signals.isThemeListenerAttached)
    }

    fun `test detaching twice does not throw`() {
        val component = JPanel()
        val signals = HostPresentationSignals(component)
        signals.attach()

        signals.detach()
        signals.detach()
    }
}
