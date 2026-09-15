package dev.acme.adbtoolbox.intellij.apps

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.apps.AppsRow
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
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

    private val tile = JPanel().apply {
        preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
        isOpaque = true
    }

    private val titleLabel = JBLabel()
    private val packageLabel = JBLabel()

    private val debugTag = JBLabel("debug").apply {
        isOpaque = true
        foreground = AdbToolboxTheme.Colors.brand
        background = AdbToolboxTheme.Colors.brandBg
        font = AdbToolboxTheme.Typography.groupLabel.deriveFont(JBUI.scale(9f))
        border = BorderFactory.createCompoundBorder(
            SolidChipBorder(AdbToolboxTheme.Colors.brandBorder),
            JBUI.Borders.empty(1, 4),
        )
    }

    private val textStack = JPanel(GridLayout(2, 1)).apply {
        isOpaque = false
        add(titleLabel)
        add(packageLabel)
    }

    private val root = JPanel(BorderLayout(AdbToolboxTheme.Spacing.s4, 0)).apply {
        add(tile, BorderLayout.WEST)
        add(textStack, BorderLayout.CENTER)
        add(debugTag, BorderLayout.EAST)
    }

    internal val tileForTest: JComponent get() = tile
    internal val titleLabelForTest: JBLabel get() = titleLabel
    internal val packageLabelForTest: JBLabel get() = packageLabel
    internal val debugTagForTest: JComponent get() = debugTag
    internal val rootForTest: JComponent get() = root

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
        tile.background = if (debuggable) AdbToolboxTheme.Colors.brandBg else AdbToolboxTheme.Colors.header
        tile.border = SolidChipBorder(if (debuggable) AdbToolboxTheme.Colors.brandBorder else AdbToolboxTheme.Colors.border)
        debugTag.isVisible = debuggable

        root.isOpaque = value.isSelected
        root.background = if (value.isSelected) AdbToolboxTheme.Colors.accentBg else null
        root.border = if (value.isSelected) {
            SolidChipBorder(AdbToolboxTheme.Colors.accentBorder)
        } else {
            JBUI.Borders.empty(1)
        }

        return root
    }
}
