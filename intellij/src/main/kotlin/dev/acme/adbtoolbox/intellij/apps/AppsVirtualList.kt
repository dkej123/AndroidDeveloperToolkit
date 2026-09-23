package dev.acme.adbtoolbox.intellij.apps

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.apps.AppsRow
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.FlexRowLayout
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
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
 * mirroring [dev.acme.adbtoolbox.intellij.devicebar.DevicePickerListPanel]'s established
 * click-to-select-by-exact-key shape. Row chrome (task 045, `design/README.md` §4: 34px rows, a
 * 16px debuggable/non-debuggable tile, the "debug" tag, and the selected-row treatment) lives in
 * [AppsRowRenderer].
 */
class AppsVirtualList(
    val virtualModel: AppsVirtualListModel = AppsVirtualListModel(),
    private val onSelect: (String) -> Unit,
) : JBList<AppsRow>(virtualModel) {

    private val clickListener = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            val index = locationToIndex(e.point)
            if (index in 0 until model.size) {
                onSelect(model.getElementAt(index).packageName)
            }
        }
    }

    private val rowRenderer = AppsRowRenderer()

    init {
        cellRenderer = rowRenderer
        // `appListStyle`: `padding: 4px 6px` around 34px rows.
        fixedCellHeight = AdbToolboxTheme.Sizes.appRow
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
 * `design/README.md` §4's row shape: a 16px app tile (radius 4; debuggable rows get `brandBg` +
 * `brandBorder`, others `header` + `border`), a two-line label/package text stack (label 700 when
 * selected, package mono `textFaint`, both meant to ellipsise at real width), and a "debug" tag
 * shown only for debuggable rows. The selected row gets `accentBg` + a 1px `accentBorder` — matching
 * [dev.acme.adbtoolbox.intellij.ui.common.PresetChip]'s selected treatment for the same design
 * system rather than inventing a new one.
 */
internal class AppsRowRenderer : ListCellRenderer<AppsRow> {

    // `iconStyle`: 16px square tile, radius 4.
    private val tile = RoundedSurface(null, null, radius = { AdbToolboxTheme.Radii.field }).apply {
        preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
    }

    private val titleLabel = JBLabel()
    private val packageLabel = JBLabel()

    // `tagStyle`: 9px/700 teal text on `brandBg`, 1px `brandBorder`, radius 3, padding 1px 4px.
    private val debugTagLabel = JBLabel("debug").apply {
        foreground = AdbToolboxTheme.Colors.brand
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(9f))
        border = JBUI.Borders.empty(1, 4)
    }
    private val debugTag = RoundedSurface(AdbToolboxTheme.Colors.brandBg, AdbToolboxTheme.Colors.brandBorder, radius = { JBUI.scale(3) }).apply {
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
        layout = FlexRowLayout(JBUI.scale(7))
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s4)
        add(tile)
        add(textStack, FlexRowLayout.FILL)
        add(debugTag)
    }

    internal val tileForTest: RoundedSurface get() = tile
    internal val titleLabelForTest: JBLabel get() = titleLabel
    internal val packageLabelForTest: JBLabel get() = packageLabel
    internal val debugTagForTest: JComponent get() = debugTag
    internal val rootForTest: RoundedSurface get() = root

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
            JBUI.scale(11.5f),
        )

        packageLabel.text = value.packageName
        packageLabel.foreground = AdbToolboxTheme.Colors.textFaint
        packageLabel.font = AdbToolboxTheme.Typography.monoMeta

        val debuggable = value.isDebuggable == true
        tile.fill = if (debuggable) AdbToolboxTheme.Colors.brandBg else AdbToolboxTheme.Colors.header
        tile.outline = if (debuggable) AdbToolboxTheme.Colors.brandBorder else AdbToolboxTheme.Colors.border
        debugTag.isVisible = debuggable

        root.fill = if (value.isSelected) AdbToolboxTheme.Colors.accentBg else null
        root.outline = if (value.isSelected) AdbToolboxTheme.Colors.accentBorder else null

        // JList's CellRendererPane paints renderer components without validating nested layout
        // managers. Give this compound renderer its final row bounds explicitly so its tile,
        // two-line text stack and debug tag are paintable in both the IDE and off-screen tests.
        val rowWidth = (list.width - list.insets.left - list.insets.right).coerceAtLeast(1)
        root.setSize(rowWidth, AdbToolboxTheme.Sizes.appRow)
        root.doLayout()
        textStack.doLayout()
        debugTag.doLayout()

        return root
    }
}
