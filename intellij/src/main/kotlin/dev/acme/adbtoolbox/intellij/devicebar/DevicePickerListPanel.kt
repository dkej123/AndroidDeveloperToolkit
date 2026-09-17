package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.devicebar.DevicePickerItem
import dev.acme.adbtoolbox.application.devicebar.DevicePickerState
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceConnectionKind
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.StatusDotIcon
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * The device picker popup's list (task 011, `design/README.md` §1's "Device picker popup"), with
 * task 043's supplied visual treatment applied: `panel`-background/`borderStrong`-bordered shell,
 * the uppercase "Connected devices" header, 28px rows (status dot, name, mono serial, connection
 * chip, `accentBg` selected-row fill), and the `header`-background footer with the
 * "Pair device over Wi-Fi…" link and the "↑↓ to select · ⏎ to apply" hint. Mounted as one bounded
 * overlay in [dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel.overlays].
 *
 * Interactions are unchanged from task 011: mouse click to select immediately, Up/Down to move the
 * keyboard highlight ([JBList]'s own native arrow-key browsing, surfaced via [onHighlightChange]),
 * Enter to apply whichever row is currently highlighted, and Escape to dismiss.
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

    private val headerLabel = JBLabel("CONNECTED DEVICES").apply {
        font = AdbToolboxTheme.Typography.groupLabel
        foreground = AdbToolboxTheme.Colors.textFaint
        border = BorderFactory.createEmptyBorder(7, 10, 4, 10)
    }

    private val hintLabel = JBLabel("↑↓ to select · ⏎ to apply").apply {
        font = AdbToolboxTheme.Typography.monoMeta
        foreground = AdbToolboxTheme.Colors.textFaint
    }

    private val pairOverWifiButton = JButton("Pair device over Wi-Fi…").apply {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        foreground = AdbToolboxTheme.Colors.accent
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD)
        addActionListener { onPairOverWifi() }
    }

    private val footer = JBPanel<Nothing>(BorderLayout()).apply {
        background = AdbToolboxTheme.Colors.header
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AdbToolboxTheme.Colors.border),
            BorderFactory.createEmptyBorder(6, 10, 6, 10),
        )
        add(pairOverWifiButton, BorderLayout.WEST)
        add(hintLabel, BorderLayout.EAST)
    }

    /** Guards [update]'s own `list.selectedIndex` write from re-entering [onHighlightChange]. */
    private var applyingExternalState = false

    init {
        background = AdbToolboxTheme.Colors.panel
        border = BorderFactory.createLineBorder(AdbToolboxTheme.Colors.borderStrong, 1)

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.isOpaque = false
        list.fixedCellHeight = AdbToolboxTheme.Sizes.listRow
        list.cellRenderer = DeviceRowRenderer()

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

        add(headerLabel, BorderLayout.NORTH)
        add(list, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
    }

    /** Test/verification seam: the rows currently rendered, in order. */
    val renderedItems: List<DevicePickerItem> get() = (0 until model.size()).map(model::getElementAt)

    /** Moves keyboard focus into the row list — called once when the popup opens, since opening it
     * via a keyboard-driven [onSelect]-adjacent trigger (Enter/Space on the device bar's selector
     * button) would otherwise leave focus on that now-hidden-behind-the-popup button, stranding a
     * keyboard-only user with a visible popup they cannot navigate with Up/Down/Enter/Escape. */
    fun focusList() {
        focusListCallCountForTest++
        list.requestFocusInWindow()
    }

    /** Test-only visibility hook: counts [focusList] invocations — `requestFocusInWindow()` itself is
     * a silent no-op in this headless test sandbox's undisplayed windows, so this is how a test
     * proves the coordinator asked for focus at all. */
    internal var focusListCallCountForTest: Int = 0
        private set

    /** Test-only visibility hook so a test can simulate real key/mouse events without a live display. */
    internal val listComponentForTest: JBList<DevicePickerItem> get() = list

    /** Test-only visibility hook: the footer's "Pair device over Wi-Fi…" button (nested, not a direct child). */
    internal val pairOverWifiButtonForTest: JButton get() = pairOverWifiButton

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

/** One 28px picker row: status dot, name (bold when selected), mono serial, connection chip. */
private class DeviceRowRenderer : ListCellRenderer<DevicePickerItem> {

    override fun getListCellRendererComponent(
        list: javax.swing.JList<out DevicePickerItem>,
        value: DevicePickerItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val row = JBPanel<Nothing>(FlowLayout(FlowLayout.LEADING, AdbToolboxTheme.Spacing.s3, 0))
        row.isOpaque = true
        row.background = if (value.isSelected) AdbToolboxTheme.Colors.accentBg else AdbToolboxTheme.Colors.panel
        row.border = BorderFactory.createEmptyBorder(0, AdbToolboxTheme.Spacing.s4, 0, AdbToolboxTheme.Spacing.s4)

        val dotColor = when (value.connectionState) {
            DeviceConnectionState.Online -> AdbToolboxTheme.Colors.green
            DeviceConnectionState.Unauthorized -> AdbToolboxTheme.Colors.amber
            else -> AdbToolboxTheme.Colors.textFaint
        }
        row.add(JBLabel(StatusDotIcon(dotColor, filled = true)))

        val name = value.model ?: value.product ?: value.serial.toString()
        row.add(
            JBLabel(name).apply {
                font = AdbToolboxTheme.Typography.body.deriveFont(if (value.isSelected) Font.BOLD else Font.PLAIN)
                foreground = AdbToolboxTheme.Colors.text
            },
        )
        row.add(
            JBLabel(value.serial.toString()).apply {
                font = AdbToolboxTheme.Typography.monoMeta
                foreground = AdbToolboxTheme.Colors.textFaint
            },
        )
        row.add(
            JBLabel(chipText(value.connectionKind)).apply {
                font = AdbToolboxTheme.Typography.groupLabel.deriveFont(9f)
                foreground = AdbToolboxTheme.Colors.textFaint
            },
        )
        return row
    }

    private fun chipText(kind: DeviceConnectionKind) = when (kind) {
        DeviceConnectionKind.Usb -> "USB"
        DeviceConnectionKind.Wifi -> "Wi-Fi"
    }
}
