package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.devicebar.DevicePickerItem
import dev.acme.adbtoolbox.application.devicebar.DevicePickerState
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceConnectionKind
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

private fun item(serial: String, model: String? = "Pixel_5", selected: Boolean = false) = DevicePickerItem(
    serial = DeviceSerial.of(serial),
    model = model,
    product = null,
    connectionKind = DeviceConnectionKind.of(serial),
    connectionState = DeviceConnectionState.Online,
    isSelected = selected,
)

/** [DevicePickerListPanel] renders task 011's [DevicePickerState] and wires mouse/keyboard interactions. */
class DevicePickerListPanelTest : BasePlatformTestCase() {

    private fun panel(
        onSelect: (DeviceSerial) -> Unit = {},
        onHighlightChange: (Int) -> Unit = {},
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onPairOverWifi: () -> Unit = {},
    ) = DevicePickerListPanel(onSelect, onHighlightChange, onConfirm, onDismiss, onPairOverWifi)

    fun `test an empty picker renders no rows`() {
        val p = panel()

        p.update(DevicePickerState())

        assertEquals(0, p.renderedItems.size)
    }

    fun `test items render in order, keyed by exact serial`() {
        val p = panel()
        val items = listOf(item("AAA"), item("BBB"), item("192.168.1.42:5555"))

        p.update(DevicePickerState(isOpen = true, items = items))

        assertEquals(items.map { it.serial }, p.renderedItems.map { it.serial })
    }

    fun `test clicking a row invokes onSelect with its exact serial`() {
        var selected: DeviceSerial? = null
        val p = panel(onSelect = { serial -> selected = serial })
        val items = listOf(item("AAA", model = "Pixel_5"), item("BBB", model = "Pixel_5"))
        p.update(DevicePickerState(isOpen = true, items = items))
        p.size = java.awt.Dimension(200, 200)
        p.doLayout()

        val list = p.listComponentForTest
        list.setBounds(0, 0, 200, 200)
        list.doLayout()
        val cellBounds = list.getCellBounds(1, 1)
        for (listener in list.mouseListeners) {
            listener.mouseClicked(
                MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cellBounds.x + 1, cellBounds.y + 1, 1, false),
            )
        }

        assertEquals(DeviceSerial.of("BBB"), selected)
    }

    fun `test pressing Enter invokes onConfirm`() {
        var confirmed = false
        val p = panel(onConfirm = { confirmed = true })
        p.update(DevicePickerState(isOpen = true, items = listOf(item("AAA")), highlightedIndex = 0))

        val list = p.listComponentForTest
        for (listener in list.keyListeners) {
            listener.keyPressed(KeyEvent(list, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER))
        }

        assertTrue(confirmed)
    }

    fun `test pressing Escape invokes onDismiss`() {
        var dismissed = false
        val p = panel(onDismiss = { dismissed = true })
        p.update(DevicePickerState(isOpen = true, items = listOf(item("AAA")), highlightedIndex = 0))

        val list = p.listComponentForTest
        for (listener in list.keyListeners) {
            listener.keyPressed(KeyEvent(list, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE))
        }

        assertTrue(dismissed)
    }

    fun `test moving native list selection (Up Down) invokes onHighlightChange`() {
        var highlighted = -1
        val p = panel(onHighlightChange = { index -> highlighted = index })
        p.update(DevicePickerState(isOpen = true, items = listOf(item("AAA"), item("BBB")), highlightedIndex = 0))

        p.listComponentForTest.selectedIndex = 1

        assertEquals(1, highlighted)
    }

    fun `test updating the picker state does not itself trigger onHighlightChange`() {
        var callCount = 0
        val p = panel(onHighlightChange = { callCount++ })

        p.update(DevicePickerState(isOpen = true, items = listOf(item("AAA"), item("BBB")), highlightedIndex = 1))

        assertEquals(0, callCount)
        assertEquals(1, p.listComponentForTest.selectedIndex)
    }

    fun `test clicking the pair-over-wifi button invokes onPairOverWifi`() {
        var requested = false
        val p = panel(onPairOverWifi = { requested = true })

        val button = p.components.filterIsInstance<javax.swing.JButton>().first()
        button.doClick()

        assertTrue(requested)
    }
}
