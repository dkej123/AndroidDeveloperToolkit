package dev.acme.adbtoolbox.intellij.apps

import dev.acme.adbtoolbox.application.apps.AppsRow
import javax.swing.AbstractListModel
import javax.swing.SwingUtilities

/**
 * [AppsVirtualList]'s data model (task 022): a plain `javax.swing.JList`-native virtualized list —
 * `JList` only ever calls [getElementAt] for the rows a viewport currently needs, so this class only
 * has to hold/replace the full (already filtered/sorted) row snapshot
 * [dev.acme.adbtoolbox.application.apps.AppsViewModel] computes on every reduce; there is no
 * separate incremental-batching concern here the way task 036's `LogcatVirtualListModel` has for its
 * unbounded append stream, since [dev.acme.adbtoolbox.application.apps.AppsViewState.rows] is always
 * a small, complete snapshot.
 *
 * [apply] must run on the EDT — enforced the same way as [dev.acme.adbtoolbox.intellij.logcat.LogcatVirtualListModel],
 * via an injectable [isEdt] so this is unit-testable without a live display.
 */
class AppsVirtualListModel(
    private val isEdt: () -> Boolean = SwingUtilities::isEventDispatchThread,
) : AbstractListModel<AppsRow>() {

    private val rows = mutableListOf<AppsRow>()

    override fun getSize(): Int = rows.size

    override fun getElementAt(index: Int): AppsRow = rows[index]

    /** Replaces the entire row snapshot, firing the minimal add/remove events a bound `JList` needs to repaint. */
    fun apply(newRows: List<AppsRow>) {
        check(isEdt()) { "Apps list mutations must run on the EDT" }
        val oldSize = rows.size
        rows.clear()
        rows += newRows
        if (oldSize > 0) fireIntervalRemoved(this, 0, oldSize - 1)
        if (rows.isNotEmpty()) fireIntervalAdded(this, 0, rows.lastIndex)
    }

    /** Test/rendering seam: the exact index of the currently selected row, or -1 if none. */
    fun indexOfSelected(): Int = rows.indexOfFirst { it.isSelected }
}
