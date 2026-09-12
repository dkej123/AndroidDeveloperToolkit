package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.devicebar.DeviceBarPresentation
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import java.awt.event.MouseEvent
import javax.swing.JButton

private val serial = DeviceSerial.of("R58N90ABCDE")
private fun device(model: String? = "Pixel_5") = Device(serial = serial, state = dev.acme.adbtoolbox.domain.device.DeviceConnectionState.Online, model = model)

/** [DeviceContextBarPanel] renders task 011's [DeviceBarPresentation]. Kept as a [BasePlatformTestCase] like every other test in this module. */
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

    fun `test online state renders the device identity and online count`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.Online(device(), onlineCount = 3))

        assertTrue(panel.selectorText.contains("Pixel_5"))
        assertTrue(panel.selectorText.contains(serial.toString()))
        assertTrue(panel.selectorText.contains("3"))
    }

    fun `test unauthorized state mentions unauthorized`() {
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.update(DeviceBarPresentation.Unauthorized(device()))

        assertTrue(panel.selectorText.contains("unauthorized", ignoreCase = true))
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

    fun `test clicking the selector invokes onToggle`() {
        var toggled = false
        val panel = DeviceContextBarPanel(onToggle = { toggled = true }, onRefresh = {})

        panel.clickSelectorForTest()

        assertTrue(toggled)
    }

    fun `test clicking the refresh button invokes onRefresh`() {
        var refreshed = false
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = { refreshed = true })

        val refreshButton = panel.components.filterIsInstance<JButton>().first()
        refreshButton.doClick()

        assertTrue(refreshed)
    }
}

/** Simulates a real mouse click on the selector without depending on actual AWT event delivery. */
private fun DeviceContextBarPanel.clickSelectorForTest() {
    val selector = this.selectorComponentForTest
    for (listener in selector.mouseListeners) {
        listener.mouseClicked(MouseEvent(selector, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 0, 0, 1, false))
    }
}
