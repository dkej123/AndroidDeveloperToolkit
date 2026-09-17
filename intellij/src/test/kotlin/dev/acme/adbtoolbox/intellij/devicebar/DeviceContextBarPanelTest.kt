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

    fun `test online state renders the device identity and a separate online count`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 3))

        assertTrue(panel.selectorText.contains("Pixel_5"))
        assertTrue(panel.selectorText.contains(serial.toString()))
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
}
