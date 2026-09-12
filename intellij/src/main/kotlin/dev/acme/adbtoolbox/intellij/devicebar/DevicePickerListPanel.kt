package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.devicebar.DevicePickerItem
import dev.acme.adbtoolbox.application.devicebar.DevicePickerState
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * The device picker popup's list (task 011, `design/README.md` §1's "Device picker popup"): a
 * [JBList] of [DevicePickerItem] rows keyed by exact serial — never by displayed text or row
 * position — mounted as one bounded overlay in
 * [dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel.overlays]. Purely structural: the final
 * `JBPopup` chrome/positioning/visual treatment is task 042's concern; this class only wires the
 * interactions `design/README.md` calls out — mouse click to select immediately, Up/Down to move
 * the keyboard highlight ([JBList]'s own native arrow-key browsing, surfaced via
 * [onHighlightChange]), Enter to apply whichever row is currently highlighted, and Escape to
 * dismiss — plus the picker footer's "Pair device over Wi-Fi…" link (task 039 stub, task 011 scope
 * only exposes the intent).
 */
class DevicePickerListPanel(
    private val onSelect: (DeviceSerial) -> Unit,
    private val onHighlightChange: (Int) -> Unit,
    private val onConfirm: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onPairOverWifi: () -> Unit,
) : JBPanel<DevicePickerListPanel>(BorderLayout()) {

    private val model = DefaultListModel<DevicePickerItem>()
    private val list = JBList(model)
    private val pairOverWifiButton = JButton("Pair device over Wi-Fi…").apply {
        addActionListener { onPairOverWifi() }
    }

    /** Guards [update]'s own `list.selectedIndex` write from re-entering [onHighlightChange]. */
    private var applyingExternalState = false

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ListCellRenderer { _, value, _, _, _ -> JBLabel(labelFor(value)) }

        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !applyingExternalState) {
                onHighlightChange(list.selectedIndex)
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index in 0 until model.size()) {
                    onSelect(model.getElementAt(index).serial)
                }
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> onConfirm()
                    KeyEvent.VK_ESCAPE -> onDismiss()
                }
            }
        })

        add(list, BorderLayout.CENTER)
        add(pairOverWifiButton, BorderLayout.SOUTH)
    }

    private fun labelFor(item: DevicePickerItem): String {
        val name = item.model ?: item.product ?: item.serial.toString()
        return "$name — ${item.serial} — ${item.connectionKind} — ${item.connectionState}"
    }

    /** Test/verification seam: the rows currently rendered, in order. */
    val renderedItems: List<DevicePickerItem> get() = (0 until model.size()).map(model::getElementAt)

    /** Test-only visibility hook so a test can simulate real key/mouse events without a live display. */
    internal val listComponentForTest: JBList<DevicePickerItem> get() = list

    fun update(picker: DevicePickerState) {
        applyingExternalState = true
        try {
            model.clear()
            picker.items.forEach(model::addElement)
            if (picker.highlightedIndex in picker.items.indices) {
                list.selectedIndex = picker.highlightedIndex
            } else {
                list.clearSelection()
            }
        } finally {
            applyingExternalState = false
        }
    }
}
