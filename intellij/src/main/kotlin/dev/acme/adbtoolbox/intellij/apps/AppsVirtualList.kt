package dev.acme.adbtoolbox.intellij.apps

import com.intellij.ui.components.JBList
import dev.acme.adbtoolbox.application.apps.AppsRow
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/**
 * Task 022's virtualized Apps-list body — a [JBList] (IntelliJ's own already-virtualized list
 * primitive: only visible rows are ever handed to [cellRenderer]) over [AppsVirtualListModel], mirroring
 * [dev.acme.adbtoolbox.intellij.devicebar.DevicePickerListPanel]'s established
 * click-to-select-by-exact-key shape rather than inventing a new one. Final row chrome (tile icon,
 * colors, spacing, "debug" tag styling per `design/README.md` §4) is a later visual-design task;
 * this renderer only proves the right data reaches each row.
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

    init {
        cellRenderer = RowRenderer()
        addMouseListener(clickListener)
    }

    /** Reflects [AppsRow.isSelected] as the actual `JList` selection, after [virtualModel] is updated. */
    fun syncSelectionFromRows() {
        val index = virtualModel.indexOfSelected()
        if (index in 0 until model.size) selectedIndex = index else clearSelection()
    }

    /** Test/disposal seam: releases the mouse listener this class installed on itself. */
    fun disposeList() {
        removeMouseListener(clickListener)
    }

    private class RowRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val row = value as? AppsRow ?: return component
            val parts = buildList {
                add(row.label)
                add(row.packageName)
                if (row.isDebuggable == true) add("[debug]")
            }
            text = parts.joinToString("  ")
            return component
        }
    }
}
