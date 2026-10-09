package io.github.dkej123.devicecockpit.intellij.apps

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.application.apps.AppsRow
import io.github.dkej123.devicecockpit.domain.packages.AppIcon
import io.github.dkej123.devicecockpit.intellij.icons.AdbToolboxIcons
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import io.github.dkej123.devicecockpit.intellij.ui.common.AppIconImages
import io.github.dkej123.devicecockpit.intellij.ui.common.FlexRowLayout
import io.github.dkej123.devicecockpit.intellij.ui.common.RoundedSurface
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Task 022's virtualized Apps-list body — a [JBList] (IntelliJ's own already-virtualized list
 * primitive: only visible rows are ever handed to [cellRenderer]) over [AppsVirtualListModel],
 * mirroring [io.github.dkej123.devicecockpit.intellij.devicebar.DevicePickerListPanel]'s established
 * click-to-select-by-exact-key shape. Row chrome (task 045, `design/README.md` §4: 34px rows, a
 * 16px debuggable/non-debuggable tile, the "debug" tag, and the selected-row treatment) lives in
 * [AppsRowRenderer].
 */
class AppsVirtualList(
    val virtualModel: AppsVirtualListModel = AppsVirtualListModel(),
    private val onSelect: (String) -> Unit,
    private val onTogglePin: (String) -> Unit = {},
    private val onOpenDetails: (String) -> Unit = {},
) : JBList<AppsRow>(virtualModel) {

    /** Rows are as wide as the list itself, so the list must follow the viewport or it never shrinks. */
    override fun getScrollableTracksViewportWidth(): Boolean = true

    private val clickListener = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            val index = locationToIndex(e.point)
            if (index !in 0 until model.size) return
            val bounds = getCellBounds(index, index) ?: return
            val row = model.getElementAt(index)
            // The app icon (left edge) opens its details, the pin (right edge) toggles it; anywhere
            // else selects the row (see AppsRowRenderer).
            when {
                e.x >= bounds.x + bounds.width - PIN_HIT_WIDTH() -> onTogglePin(row.packageName)
                e.x < bounds.x + ICON_HIT_WIDTH() && e.y >= bounds.y + bounds.height - AdbToolboxTheme.Sizes.appRow -> {
                    onSelect(row.packageName)
                    onOpenDetails(row.packageName)
                }
                else -> onSelect(row.packageName)
            }
        }
    }

    private val rowRenderer = AppsRowRenderer()

    init {
        cellRenderer = rowRenderer
        // `appListStyle`: `padding: 4px 6px` around 34px rows. Heights vary: a row that starts a
        // "Pinned"/"All apps" section also carries that section's header.
        fixedCellHeight = -1
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s2, AdbToolboxTheme.Spacing.s3)
        isOpaque = false
        addMouseListener(clickListener)
    }

    /**
     * Test seam: the single renderer instance this list paints every row with. [JBList] wraps
     * whatever is assigned to [cellRenderer] in its own `ExpandedItemListCellRendererWrapper`, so
     * that property's getter cannot be cast back to [AppsRowRenderer] — this keeps a direct
     * reference instead.
     */
    internal val rowRendererForTest: AppsRowRenderer get() = rowRenderer

    /** Reflects [AppsRow.isSelected] as the actual `JList` selection, after [virtualModel] is updated. */
    fun syncSelectionFromRows() {
        val index = virtualModel.indexOfSelected()
        if (index in 0 until model.size) selectedIndex = index else clearSelection()
    }

    /** Test/disposal seam: releases the mouse listener this class installed on itself. */
    fun disposeList() {
        removeMouseListener(clickListener)
    }
}

/**
 * `design/README.md` §4's row shape: a 16px app tile (the app's launcher icon when the device
 * reported one; otherwise radius 4, debuggable rows get `brandBg` + `brandBorder`, others
 * `header` + `border`), a two-line label/package text stack (label 700 when
 * selected, package mono `textFaint`, both meant to ellipsise at real width), and a "debug" tag
 * shown only for debuggable rows. The selected row gets `accentBg` + a 1px `accentBorder` — matching
 * [io.github.dkej123.devicecockpit.intellij.ui.common.PresetChip]'s selected treatment for the same design
 * system rather than inventing a new one.
 */
/** Width, from the row's right edge, that a click treats as a click on the pin. */
private val PIN_HIT_WIDTH: () -> Int = { JBUIScale.scale(28) }

/** Width, from the row's left edge, that a click treats as a click on the app icon. */
private val ICON_HIT_WIDTH: () -> Int = { JBUIScale.scale(30) }

internal class AppsRowRenderer : ListCellRenderer<AppsRow> {

    private val sectionHeader = JBLabel().apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(9.5f))
        foreground = AdbToolboxTheme.Colors.textFaint
        border = JBUI.Borders.empty(6, 8, 2, 8)
    }

    private val pin = JBLabel().apply {
        border = JBUI.Borders.emptyLeft(2)
    }

    // `iconStyle`: 16px square tile, radius 4 — replaced by the app's own launcher icon when known.
    private val tile = AppIconTile().apply {
        preferredSize = Dimension(JBUIScale.scale(16), JBUIScale.scale(16))
        toolTipText = "App details"
    }

    private val titleLabel = JBLabel()
    private val packageLabel = JBLabel()

    // `tagStyle`: 9px/700 teal text on `brandBg`, 1px `brandBorder`, radius 3, padding 1px 4px.
    private val debugTagLabel = JBLabel("debug").apply {
        foreground = AdbToolboxTheme.Colors.brand
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(9f))
        border = JBUI.Borders.empty(1, 4)
    }
    private val debugTag = RoundedSurface(AdbToolboxTheme.Colors.brandBg, AdbToolboxTheme.Colors.brandBorder, radius = { JBUIScale.scale(3) }).apply {
        layout = BorderLayout()
        add(debugTagLabel, BorderLayout.CENTER)
    }

    private val textStack = JPanel(GridLayout(2, 1)).apply {
        isOpaque = false
        add(titleLabel)
        add(packageLabel)
    }

    // `rowStyle`: 34px, radius 5, `padding: 0 8px`, gap 7, selected = `accentBg` + 1px `accentBorder`.
    private val root = RoundedSurface(null, null).apply {
        layout = FlexRowLayout(JBUIScale.scale(7))
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s4)
        add(tile)
        add(textStack, FlexRowLayout.FILL)
        add(debugTag)
        add(pin)
    }

    // Opaque with the list's own background: JList paints its selection color behind a non-opaque
    // renderer, which would tint a section header sitting above the selected row.
    private val cell = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = AdbToolboxTheme.Colors.bg
        add(sectionHeader, BorderLayout.NORTH)
        add(root, BorderLayout.CENTER)
    }

    internal val tileForTest: AppIconTile get() = tile
    internal val titleLabelForTest: JBLabel get() = titleLabel
    internal val packageLabelForTest: JBLabel get() = packageLabel
    internal val debugTagForTest: JComponent get() = debugTag
    internal val rootForTest: RoundedSurface get() = root
    internal val sectionHeaderForTest: JBLabel get() = sectionHeader
    internal val pinForTest: JBLabel get() = pin

    override fun getListCellRendererComponent(
        list: JList<out AppsRow>,
        value: AppsRow,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        titleLabel.text = value.label
        titleLabel.foreground = AdbToolboxTheme.Colors.text
        titleLabel.font = AdbToolboxTheme.Typography.body.deriveFont(
            if (value.isSelected) Font.BOLD else Font.PLAIN,
            JBUIScale.scale(11.5f),
        )

        packageLabel.text = value.packageName
        packageLabel.foreground = AdbToolboxTheme.Colors.textFaint
        packageLabel.font = AdbToolboxTheme.Typography.monoMeta

        val debuggable = value.isDebuggable == true
        tile.icon = value.icon?.takeIf { AppIconImages.decode(it) != null }
        tile.fill = when {
            tile.icon != null -> null
            debuggable -> AdbToolboxTheme.Colors.brandBg
            else -> AdbToolboxTheme.Colors.header
        }
        tile.outline = when {
            tile.icon != null -> null
            debuggable -> AdbToolboxTheme.Colors.brandBorder
            else -> AdbToolboxTheme.Colors.border
        }
        debugTag.isVisible = debuggable

        root.fill = if (value.isSelected) AdbToolboxTheme.Colors.accentBg else null
        root.outline = if (value.isSelected) AdbToolboxTheme.Colors.accentBorder else null

        // JList's CellRendererPane paints renderer components without validating nested layout
        // managers. Give this compound renderer its final row bounds explicitly so its tile,
        // two-line text stack and debug tag are paintable in both the IDE and off-screen tests.
        pin.icon = if (value.isPinned) AdbToolboxIcons.Actions.pinned else AdbToolboxIcons.Actions.pin
        pin.toolTipText = if (value.isPinned) "Unpin" else "Pin to the top"
        sectionHeader.isVisible = value.sectionHeader != null
        sectionHeader.text = value.sectionHeader?.uppercase().orEmpty()

        val rowWidth = (list.width - list.insets.left - list.insets.right).coerceAtLeast(1)
        val headerHeight = if (sectionHeader.isVisible) sectionHeader.preferredSize.height else 0
        cell.preferredSize = Dimension(rowWidth, headerHeight + AdbToolboxTheme.Sizes.appRow)
        cell.setSize(rowWidth, headerHeight + AdbToolboxTheme.Sizes.appRow)
        cell.doLayout()
        root.doLayout()
        textStack.doLayout()
        debugTag.doLayout()

        return cell
    }
}

/** The row's 16px tile: paints [icon] scaled into its bounds when set, the rounded tile otherwise. */
internal class AppIconTile : RoundedSurface(null, null, radius = { AdbToolboxTheme.Radii.field }) {
    var icon: AppIcon? = null

    override fun paintComponent(g: Graphics) {
        val current = icon
        if (current == null || !AppIconImages.paint(g, current, 0, 0, width, height)) super.paintComponent(g)
    }
}
