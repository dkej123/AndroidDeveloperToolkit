package dev.acme.adbtoolbox.intellij.host

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.ComponentEvent
import javax.swing.JLabel

/**
 * [AdbToolboxHostPanel] is the neutral root Swing host (task 010): named slots for device
 * context, navigation, active view, and feedback, plus a bounded overlay layer, all tied to this
 * panel's own [com.intellij.openapi.Disposable] lifecycle. Kept as a [BasePlatformTestCase] like
 * every other test in this module (see `AdbTransportSelectionTest`'s class doc).
 */
class AdbToolboxHostPanelTest : BasePlatformTestCase() {

    fun `test the named slots exist and are addressable`() {
        val host = AdbToolboxHostPanel()

        assertNotNull(host.deviceContextSlot)
        assertNotNull(host.navigationSlot)
        assertNotNull(host.activeViewHost)
        assertNotNull(host.feedbackSlot)
        assertNotNull(host.overlays)
    }

    fun `test a feature view registers through the host without any central switch`() {
        val host = AdbToolboxHostPanel()
        val view = JLabel("device")

        val registered = host.registerFeatureView("device") { view }

        assertSame(view, registered)
        assertTrue(host.activeViewHost.isRegistered("device"))
    }

    fun `test switching feature views through the host preserves each instance across two round trips`() {
        val host = AdbToolboxHostPanel()
        val deviceView = JLabel("device")
        val appsView = JLabel("apps")
        host.registerFeatureView("device") { deviceView }
        host.registerFeatureView("apps") { appsView }

        host.showFeatureView("apps")
        host.showFeatureView("device")
        host.showFeatureView("apps")
        host.showFeatureView("device")

        assertSame(deviceView, host.activeViewHost.componentFor("device"))
        assertSame(appsView, host.activeViewHost.componentFor("apps"))
    }

    fun `test an overlay shown but never dismissed is torn down when the host is disposed`() {
        val host = AdbToolboxHostPanel()
        val overlay = JLabel("toast")
        host.overlays.show(overlay)
        assertTrue(host.overlays.isShowing(overlay))

        host.dispose()

        assertFalse(host.overlays.isShowing(overlay))
        assertNull(overlay.parent)
    }

    fun `test disposing the host removes the width listener`() {
        val host = AdbToolboxHostPanel()
        assertTrue(host.componentListeners.isNotEmpty())

        host.dispose()

        assertTrue(host.componentListeners.isEmpty())
    }

    fun `test the host width flow reflects a real resize once the resize listener fires`() {
        val host = AdbToolboxHostPanel()
        host.setSize(500, 400)

        host.componentListeners.forEach { it.componentResized(ComponentEvent(host, ComponentEvent.COMPONENT_RESIZED)) }

        assertEquals(500, host.hostWidth.value)
    }

    fun `test disposing the host twice does not throw`() {
        val host = AdbToolboxHostPanel()

        host.dispose()
        host.dispose()
    }
}
