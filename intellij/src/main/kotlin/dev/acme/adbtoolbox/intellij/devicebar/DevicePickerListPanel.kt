package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.devicebar.DevicePickerItem
import dev.acme.adbtoolbox.application.devicebar.DevicePickerState
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceConnectionKind
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.FlexRowLayout
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.StatusDotIcon
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
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
        border = JBUI.Borders.empty(7, 10, 4, 10)
    }

    private val hintLabel = JBLabel("↑↓ to select · ⏎ to apply").apply {
        font = AdbToolboxTheme.Typography.mono.deriveFont(JBUIScale.scale(9f))
        foreground = AdbToolboxTheme.Colors.textFaint
    }

    private val pairOverWifiButton: JButton = DesignButton("Pair device over Wi-Fi…", DesignButtonStyle.LINK).apply {
        addActionListener { onPairOverWifi() }
    }

    private val footer = JBPanel<Nothing>(BorderLayout()).apply {
        background = AdbToolboxTheme.Colors.header
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AdbToolboxTheme.Colors.border),
            JBUI.Borders.empty(6, 10),
        )
        add(pairOverWifiButton, BorderLayout.WEST)
        add(hintLabel, BorderLayout.EAST)
    }

    /** Guards [update]'s own `list.selectedIndex` write from re-entering [onHighlightChange]. */
    private var applyingExternalState = false

    /** No max height until more than [MAX_ROWS] devices; then the list scrolls and header/footer stay put. */
    private val listScroll = object : JBScrollPane(list) {
        override fun getPreferredSize(): Dimension =
            Dimension(super.getPreferredSize().width, AdbToolboxTheme.Sizes.listRow * model.size().coerceIn(1, MAX_ROWS))
    }.apply {
        border = BorderFactory.createEmptyBorder()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    init {
        background = AdbToolboxTheme.Colors.panel
        isOpaque = false
        // 1px for the rounded `borderStrong` outline painted in paintBorder.
        border = JBUI.Borders.empty(1)

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
        // Full-width hover fill: the pointer moves the same highlight as ↑↓.
        list.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index in 0 until model.size() && index != list.selectedIndex) list.selectedIndex = index
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
        add(listScroll, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
    }

    // Container: `panel` fill, 1px `borderStrong`, radius 8, content clipped to the rounded shape.
    private fun outline(): java.awt.geom.RoundRectangle2D.Float {
        val arc = JBUIScale.scale(16f)
        return java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = AdbToolboxTheme.Colors.panel
            g2.fill(outline())
        } finally {
            g2.dispose()
        }
    }

    override fun paintChildren(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.clip(outline())
            super.paintChildren(g2)
        } finally {
            g2.dispose()
        }
    }

    override fun paintBorder(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = AdbToolboxTheme.Colors.borderStrong
            g2.stroke = java.awt.BasicStroke(JBUIScale.scale(1f))
            g2.draw(outline())
        } finally {
            g2.dispose()
        }
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

/**
 * One 28px picker row (design §1, `screenshots/device-picker.png`): padding 0 10, gap 7, items
 * centred — 7px state dot (green online, amber unauthorized) · name 11.5px, bold for the current
 * device, never truncated · mono 9.5px `textFaint` serial taking the remaining width with an
 * ellipsis · right-aligned connection chip (9px bold, padding 1 4, radius 3, 1px `border` +
 * `textDim`; amber border and text for an unauthorized device). The current device's row is a
 * full-bleed `accentBg`; the keyboard/hover highlight on another row is the `hover` fill.
 */
private class DeviceRowRenderer : ListCellRenderer<DevicePickerItem> {

    private val dotLabel = JBLabel()
    private val nameLabel = JBLabel()
    private val serialLabel = JBLabel().apply {
        font = AdbToolboxTheme.Typography.monoMeta
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val chipLabel = JBLabel().apply {
        font = AdbToolboxTheme.Typography.groupLabel.deriveFont(JBUIScale.scale(9f))
    }
    private val row = object : JBPanel<Nothing>(FlexRowLayout(JBUIScale.scale(7))) {
        override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, AdbToolboxTheme.Sizes.listRow)
    }.apply {
        isOpaque = true
        border = BorderFactory.createEmptyBorder(0, AdbToolboxTheme.Spacing.sectionInset, 0, AdbToolboxTheme.Spacing.sectionInset)
        add(dotLabel)
        add(nameLabel)
        add(serialLabel, FlexRowLayout.FILL)
        add(chipLabel)
    }

    override fun getListCellRendererComponent(
        list: javax.swing.JList<out DevicePickerItem>,
        value: DevicePickerItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        // `accentBg` and `hover` are translucent: compose them over the popup's `panel` so the list's
        // own selection painting underneath never shows through.
        row.background = when {
            value.isSelected -> blend(AdbToolboxTheme.Colors.panel, AdbToolboxTheme.Colors.accentBg)
            isSelected -> blend(AdbToolboxTheme.Colors.panel, AdbToolboxTheme.Colors.hover)
            else -> AdbToolboxTheme.Colors.panel
        }
        val unauthorized = value.connectionState == DeviceConnectionState.Unauthorized
        dotLabel.icon = StatusDotIcon(
            when (value.connectionState) {
                DeviceConnectionState.Online -> AdbToolboxTheme.Colors.green
                DeviceConnectionState.Unauthorized -> AdbToolboxTheme.Colors.amber
                else -> AdbToolboxTheme.Colors.textFaint
            },
            filled = true,
        )
        nameLabel.text = value.model ?: value.product ?: value.serial.toString()
        nameLabel.font = AdbToolboxTheme.Typography.body.deriveFont(if (value.isSelected) Font.BOLD else Font.PLAIN, JBUIScale.scale(11.5f))
        nameLabel.foreground = AdbToolboxTheme.Colors.text
        serialLabel.text = value.serial.toString()
        chipLabel.text = chipText(value.connectionKind)
        chipLabel.foreground = if (unauthorized) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.textDim
        chipLabel.border = BorderFactory.createCompoundBorder(
            SolidChipBorder(if (unauthorized) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.border, radius = { JBUIScale.scale(3) }),
            JBUI.Borders.empty(1, 4),
        )
        row.setSize(list.width, AdbToolboxTheme.Sizes.listRow)
        row.doLayout()
        return row
    }

    /** [over] composed onto the opaque [base]. */
    private fun blend(base: java.awt.Color, over: java.awt.Color): java.awt.Color {
        val a = over.alpha / 255f
        fun mix(b: Int, o: Int) = (b * (1 - a) + o * a).toInt()
        return java.awt.Color(mix(base.red, over.red), mix(base.green, over.green), mix(base.blue, over.blue))
    }

    private fun chipText(kind: DeviceConnectionKind) = when (kind) {
        DeviceConnectionKind.Usb -> "USB"
        DeviceConnectionKind.Wifi -> "Wi-Fi"
        DeviceConnectionKind.Emulator -> "Emulator"
    }
}

private const val MAX_ROWS = 8
