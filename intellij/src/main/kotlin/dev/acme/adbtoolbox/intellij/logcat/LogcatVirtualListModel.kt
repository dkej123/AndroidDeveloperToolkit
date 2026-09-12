package dev.acme.adbtoolbox.intellij.logcat

import javax.swing.AbstractListModel
import javax.swing.SwingUtilities

/** Virtual-list data model for task 036. Behavior is implemented after its failing tests. */
class LogcatVirtualListModel(
    private val maxRows: Int = DEFAULT_MAX_RENDER_ROWS,
    private val isEdt: () -> Boolean = SwingUtilities::isEventDispatchThread,
) : AbstractListModel<LogcatRenderRow>() {
    constructor(isEdt: () -> Boolean) : this(DEFAULT_MAX_RENDER_ROWS, isEdt)

    private val rows = mutableListOf<LogcatRenderRow>()
    var droppedRows: Long = 0
        private set

    init {
        require(maxRows > 0)
    }

    override fun getSize(): Int = rows.size

    override fun getElementAt(index: Int): LogcatRenderRow = rows[index]

    fun apply(batch: LogcatRenderBatch) {
        check(isEdt()) { "Logcat list mutations must run on the EDT" }
        if (batch.reset) replaceAll(batch.rows)
        batch.retainFromSequence?.let(::evictBefore)
        if (!batch.reset) {
            val floor = batch.retainFromSequence
            applyDelta(batch.rows.filter { floor == null || it.sequence >= floor })
        }
        enforceRowLimit()
    }

    fun presentationChanged() {
        check(isEdt()) { "Logcat list mutations must run on the EDT" }
        if (rows.isNotEmpty()) fireContentsChanged(this, 0, rows.lastIndex)
    }

    private fun replaceAll(replacement: List<LogcatRenderRow>) {
        val oldSize = rows.size
        rows.clear()
        rows += replacement
            .associateBy { it.sequence }
            .values
            .map(LogcatRenderRow::freeze)
            .sortedBy { it.sequence }
        if (oldSize > 0) fireIntervalRemoved(this, 0, oldSize - 1)
        if (rows.isNotEmpty()) fireIntervalAdded(this, 0, rows.lastIndex)
    }

    private fun evictBefore(retainFromSequence: Long) {
        val removeCount = rows.indexOfFirst { it.sequence >= retainFromSequence }
            .let { firstRetained -> if (firstRetained < 0) rows.size else firstRetained }
        if (removeCount == 0) return
        rows.subList(0, removeCount).clear()
        fireIntervalRemoved(this, 0, removeCount - 1)
    }

    private fun applyDelta(delta: List<LogcatRenderRow>) {
        if (delta.isEmpty()) return
        val sorted = delta
            .associateBy { it.sequence }
            .values
            .map(LogcatRenderRow::freeze)
            .sortedBy { it.sequence }
        if (rows.isEmpty() || sorted.first().sequence > rows.last().sequence) {
            val insertionIndex = rows.size
            rows.addAll(sorted)
            fireIntervalAdded(this, insertionIndex, rows.lastIndex)
            return
        }
        sorted.forEach(::upsert)
    }

    private fun upsert(candidate: LogcatRenderRow) {
        val row = candidate.freeze()
        val index = rows.binarySearchBy(row.sequence) { it.sequence }
        if (index >= 0) {
            rows[index] = row
            fireContentsChanged(this, index, index)
        } else {
            val insertionIndex = -index - 1
            rows.add(insertionIndex, row)
            fireIntervalAdded(this, insertionIndex, insertionIndex)
        }
    }

    private fun enforceRowLimit() {
        val excess = rows.size - maxRows
        if (excess <= 0) return
        rows.subList(0, excess).clear()
        droppedRows += excess
        fireIntervalRemoved(this, 0, excess - 1)
    }

    companion object {
        const val DEFAULT_MAX_RENDER_ROWS = 100_000
    }
}

internal fun LogcatRenderRow.freeze(): LogcatRenderRow = copy(matchSpans = matchSpans.toList())
