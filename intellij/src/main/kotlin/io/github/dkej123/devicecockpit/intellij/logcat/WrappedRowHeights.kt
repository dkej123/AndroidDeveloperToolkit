package io.github.dkej123.devicecockpit.intellij.logcat

/**
 * Wrapped Logcat row heights in pixels, one per model row, kept in step with the model's events so
 * an appended batch measures only its own rows. Every layout used to re-check a cache key and look
 * up a boxed map entry for each of ~100k rows after every batch, pinning the EDT (2026-10-12
 * report). Everything is measured again only when the width, presentation or line height changes.
 */
internal class WrappedRowHeights(
    private val lineCount: (LogcatRenderRow, LogcatRenderPresentation, Int) -> Int = LogcatRowStyle::wrappedLineCount,
) {
    private var heights: IntArray? = null
    private var cells = -1
    private var presentation: LogcatRenderPresentation? = null
    private var lineHeight = -1

    /** The heights for a model of [size] rows; the same array while nothing changed. */
    fun pixels(
        size: Int,
        cells: Int,
        presentation: LogcatRenderPresentation,
        lineHeight: Int,
        rowAt: (Int) -> LogcatRenderRow,
    ): IntArray {
        val current = heights
        if (current != null && current.size == size && cells == this.cells && lineHeight == this.lineHeight &&
            presentation == this.presentation
        ) {
            return current
        }
        this.cells = cells
        this.presentation = presentation
        this.lineHeight = lineHeight
        return IntArray(size) { measure(rowAt(it)) }.also { heights = it }
    }

    fun added(from: Int, to: Int, cells: Int, presentation: LogcatRenderPresentation, lineHeight: Int, rowAt: (Int) -> LogcatRenderRow) {
        val current = validFor(cells, presentation, lineHeight) ?: return
        if (from > current.size) return invalidate()
        val added = to - from + 1
        heights = IntArray(current.size + added).also { next ->
            current.copyInto(next, 0, 0, from)
            for (index in from..to) next[index] = measure(rowAt(index))
            current.copyInto(next, to + 1, from, current.size)
        }
    }

    fun removed(from: Int, to: Int) {
        val current = heights ?: return
        if (to >= current.size) return invalidate()
        heights = IntArray(current.size - (to - from + 1)).also { next ->
            current.copyInto(next, 0, 0, from)
            current.copyInto(next, from, to + 1, current.size)
        }
    }

    fun changed(from: Int, to: Int, cells: Int, presentation: LogcatRenderPresentation, lineHeight: Int, rowAt: (Int) -> LogcatRenderRow) {
        val current = validFor(cells, presentation, lineHeight) ?: return
        // A change over every row is a presentation or width change: the next layout measures
        // them all with its settings, rather than now with ones that may already be stale.
        if (to >= current.size || (from == 0 && to == current.lastIndex)) return invalidate()
        for (index in from..to) current[index] = measure(rowAt(index))
    }

    private fun validFor(cells: Int, presentation: LogcatRenderPresentation, lineHeight: Int): IntArray? {
        val current = heights ?: return null
        if (cells != this.cells || lineHeight != this.lineHeight || presentation != this.presentation) {
            invalidate()
            return null
        }
        return current
    }

    /** Forgets every height; the tracker stops following the model while wrapping is off. */
    fun clear() = invalidate()

    private fun invalidate() {
        heights = null
    }

    private fun measure(row: LogcatRenderRow): Int = lineHeight * lineCount(row, presentation!!, cells)
}
