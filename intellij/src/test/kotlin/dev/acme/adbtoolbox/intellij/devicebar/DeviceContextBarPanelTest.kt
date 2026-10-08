package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.devicebar.DeviceBarPresentation
import dev.acme.adbtoolbox.domain.adb.DeviceSerial

private val serial = DeviceSerial.of("R58N90ABCDE")
private fun device(model: String? = "Pixel_5") = dev.acme.adbtoolbox.domain.device.Device(
    serial = serial,
    state = dev.acme.adbtoolbox.domain.device.DeviceConnectionState.Online,
    model = model,
)

/**
 * [DeviceContextBarPanel] renders task 011's [DeviceBarPresentation] with task 043's supplied
 * visual treatment (`design/README.md` §1). Kept as a [BasePlatformTestCase] like every other test
 * in this module.
 */
class DeviceContextBarPanelTest : BasePlatformTestCase() {

    fun `test loading state renders a querying message`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.Loading)

        assertTrue(panel.selectorText.contains("Querying", ignoreCase = true))
    }

    fun `test no device state renders a no-device message`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.NoDevice)

        assertTrue(panel.selectorText.contains("No device", ignoreCase = true))
    }

    fun `test attached but unselected devices invite opening the picker`() {
        var toggles = 0
        val panel = DeviceContextBarPanel(onToggle = { toggles++ }, onRefresh = {})

        panel.update(DeviceBarPresentation.SelectDevice(deviceCount = 2))

        assertEquals("2 devices — select one", panel.selectorText)
        assertFalse(panel.selectorText.contains("No device", ignoreCase = true))
        panel.selectorComponentForTest.doClick()
        assertEquals(1, toggles)
    }

    fun `test a device discovery failure is shown instead of no device`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.Error("adb executable not found (tried: PathFallback)"))

        assertEquals("adb executable not found (tried: PathFallback)", panel.selectorText)
    }

    fun `test online state renders the device identity and a separate online count`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 3))

        // adb's `model:Pixel_5` token is shown as the model name users know.
        assertEquals("Pixel 5", panel.selectorText)
        assertEquals(serial.toString(), panel.serialText)
        assertEquals("3 online", panel.onlineCountText)
    }

    fun `test online state over USB renders a USB connection chip`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 1))

        assertTrue(panel.connectionChipVisible)
        assertEquals("USB", panel.connectionChipText)
    }

    fun `test online state over Wi-Fi renders a Wi-Fi connection chip`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})
        val wifiSerial = DeviceSerial.of("192.168.1.42:5555")
        val wifiDevice = dev.acme.adbtoolbox.domain.device.Device(
            serial = wifiSerial,
            state = dev.acme.adbtoolbox.domain.device.DeviceConnectionState.Online,
            model = "Pixel_5",
        )

        panel.update(DeviceBarPresentation.Online(wifiDevice, onlineCount = 1))

        assertEquals("Wi-Fi", panel.connectionChipText)
    }

    fun `test unauthorized state mentions unauthorized and shows the retry link and banner`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.Unauthorized(device()))

        assertTrue(panel.selectorText.contains("unauthorized", ignoreCase = true))
        assertTrue(panel.retryComponentForTest.isVisible)
    }

    fun `test offline state mentions offline`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.Offline(device()))

        assertTrue(panel.selectorText.contains("offline", ignoreCase = true))
    }

    fun `test error state renders the error message`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.Error("Device disconnected"))

        assertEquals("Device disconnected", panel.selectorText)
    }

    fun `test a non-unauthorized state hides the retry link and banner`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})
        panel.update(DeviceBarPresentation.Unauthorized(device()))

        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 1))

        assertFalse(panel.retryComponentForTest.isVisible)
    }

    fun `test clicking the selector invokes onToggle`() {
        var toggled = false
        val panel = DeviceContextBarPanel(onToggle = { toggled = true }, onRefresh = {})

        panel.selectorComponentForTest.doClick()

        assertTrue(toggled)
    }

    fun `test clicking the refresh button invokes onRefresh`() {
        var refreshed = false
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = { refreshed = true })

        panel.refreshButtonForTest.doClick()

        assertTrue(refreshed)
    }

    fun `test clicking the retry link invokes onRefresh`() {
        var refreshed = false
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = { refreshed = true })
        panel.update(DeviceBarPresentation.Unauthorized(device()))

        panel.retryComponentForTest.doClick()

        assertTrue(refreshed)
    }

    // ---- Task 050: the selector and retry controls must be real, keyboard-operable buttons ----

    fun `test the selector is a focusable button reachable by keyboard, not a mouse-only label`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        assertTrue(panel.selectorComponentForTest.isFocusable)
    }

    fun `test Down on the focused selector opens the picker like Space`() {
        var toggles = 0
        val bar = DeviceContextBarPanel(onToggle = { toggles++ }, onRefresh = {})
        val selector = bar.selectorComponentForTest
        val down = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DOWN, 0)

        val actionKey = selector.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(down)
        selector.actionMap.get(actionKey).actionPerformed(java.awt.event.ActionEvent(selector, 0, ""))

        assertEquals(1, toggles)
    }

    fun `test the retry control is a focusable button reachable by keyboard, not a mouse-only label`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})
        panel.update(DeviceBarPresentation.Unauthorized(device()))

        assertTrue(panel.retryComponentForTest.isFocusable)
    }

    fun `test the selector exposes an accessible name matching its rendered state`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.NoDevice)

        assertEquals(panel.selectorText, panel.selectorComponentForTest.getAccessibleContext().accessibleName)
    }

    fun `test the refresh button exposes an accessible name`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        assertEquals("Refresh device list", panel.refreshButtonForTest.getAccessibleContext().accessibleName)
    }

    // ---- Task 051: the narrow width class (`design/README.md`'s responsive rule) drops the serial
    // and shortens "N online" to "N" ----

    fun `test the narrow width class drops the serial and shortens the online count`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})
        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 3))

        panel.applyResponsiveLayout(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Breakpoints.narrow - 1)

        assertEquals("", panel.serialText)
        assertEquals("3", panel.onlineCountText)
    }

    fun `test the dock width class shows the serial and the full online count`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})
        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 3))

        panel.applyResponsiveLayout(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Breakpoints.narrow)

        assertEquals(serial.toString(), panel.serialText)
        assertEquals("3 online", panel.onlineCountText)
    }

    fun `test narrowing then widening restores the serial and full online count`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})
        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 5))

        panel.applyResponsiveLayout(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Breakpoints.narrow - 1)
        panel.applyResponsiveLayout(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Breakpoints.wide)

        assertEquals(serial.toString(), panel.serialText)
        assertEquals("5 online", panel.onlineCountText)
    }

    fun `test a later update reapplies the current width class`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})
        panel.applyResponsiveLayout(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Breakpoints.narrow - 1)

        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 7))

        assertEquals("", panel.serialText)
        assertEquals("7", panel.onlineCountText)
    }

    fun `test the narrow width class does not affect non-online states`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})
        panel.update(DeviceBarPresentation.Unauthorized(device()))

        panel.applyResponsiveLayout(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Breakpoints.narrow - 1)

        assertTrue(panel.selectorText.contains("unauthorized", ignoreCase = true))
        assertTrue(panel.retryComponentForTest.isVisible)
    }

    fun `test a long device name never overlaps the required refresh action's bounds`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})
        panel.update(
            DeviceBarPresentation.Online(
                device(model = "A Very Long OEM Device Model Name That Would Overflow A Narrow Bar"),
                onlineCount = 3,
            ),
        )

        panel.setSize(260, 30)
        panel.doLayout()
        panel.refreshButtonForTest.parent.doLayout()

        val selector = panel.selectorComponentForTest
        val refresh = panel.refreshButtonForTest
        val selectorRight = javax.swing.SwingUtilities.convertPoint(selector.parent, selector.x + selector.width, 0, panel).x
        val refreshLeft = javax.swing.SwingUtilities.convertPoint(refresh.parent, refresh.x, 0, panel).x

        assertTrue("selector ends at $selectorRight, refresh starts at $refreshLeft", selectorRight <= refreshLeft)
        assertTrue(refreshLeft + refresh.width <= panel.width)
    }

    fun `test the whole selector opens the picker, like a toolbar combo box`() {
        var toggles = 0
        val panel = DeviceContextBarPanel(onToggle = { toggles++ }, onRefresh = {})
        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 1))

        listOf(panel.chipComponentForTest, panel.caretComponentForTest, panel.selectorGroupForTest).forEach { target ->
            target.dispatchEvent(java.awt.event.MouseEvent(target, java.awt.event.MouseEvent.MOUSE_CLICKED, 0L, 0, 2, 2, 1, false))
        }
        panel.selectorComponentForTest.doClick()

        assertEquals(4, toggles)
    }

    fun `test hovering the selector shows its hover fill and a hand cursor`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})
        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 1))
        val group = panel.selectorGroupForTest

        group.dispatchEvent(java.awt.event.MouseEvent(group, java.awt.event.MouseEvent.MOUSE_ENTERED, 0L, 0, 2, 2, 0, false))

        assertTrue(panel.selectorHoveredForTest)
        assertEquals(java.awt.Cursor.HAND_CURSOR, panel.caretComponentForTest.cursor.type)
    }
}
