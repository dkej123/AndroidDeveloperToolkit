package io.github.dkej123.devicecockpit.intellij.logcat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WrappedRowHeightsTest {

    private val wrap = LogcatRenderPresentation(wrapLines = true)
    private val rows = (1L..5L).map { row(it, lines = it.toInt()) }.toMutableList()
    private var measured = 0
    private val heights = WrappedRowHeights { row, presentation, cells ->
        measured++
        assertEquals(wrap.wrapLines, presentation.wrapLines)
        row.message.length / cells
    }

    /** A row whose message wraps into [lines] lines at 10 cells. */
    private fun row(sequence: Long, lines: Int) = LogcatRenderRow(sequence, null, null, null, "x".repeat(lines * 10))

    private fun pixels() = heights.pixels(rows.size, cells = 10, presentation = wrap, lineHeight = 16) { rows[it] }

    @Test
    fun `the first layout measures every row, later ones reuse the same heights`() {
        assertArrayEquals(intArrayOf(16, 32, 48, 64, 80), pixels())
        assertEquals(5, measured)

        val again = pixels()
        assertSame(again, pixels())
        assertEquals(5, measured)
    }

    @Test
    fun `appended rows are the only ones measured`() {
        pixels()
        rows += row(6, lines = 2)
        rows += row(7, lines = 1)
        heights.added(5, 6, cells = 10, presentation = wrap, lineHeight = 16) { rows[it] }

        assertArrayEquals(intArrayOf(16, 32, 48, 64, 80, 32, 16), pixels())
        assertEquals(7, measured)
    }

    @Test
    fun `rows evicted from the front and a changed row are kept in step without measuring the rest`() {
        pixels()
        repeat(2) { rows.removeAt(0) }
        heights.removed(0, 1)
        rows[1] = row(4, lines = 3)
        heights.changed(1, 1, cells = 10, presentation = wrap, lineHeight = 16) { rows[it] }

        assertArrayEquals(intArrayOf(48, 48, 80), pixels())
        assertEquals(6, measured)
    }

    @Test
    fun `a new width or presentation measures everything again`() {
        pixels()
        heights.pixels(rows.size, cells = 20, presentation = wrap, lineHeight = 16) { rows[it] }
        assertEquals(10, measured)

        heights.pixels(rows.size, cells = 20, presentation = wrap.copy(columns = LogcatColumnVisibility(tag = false)), lineHeight = 16) { rows[it] }
        assertEquals(15, measured)
    }

    @Test
    fun `a change event over every row waits for the next layout instead of measuring with stale settings`() {
        pixels()
        heights.changed(0, rows.lastIndex, cells = 10, presentation = wrap, lineHeight = 16) { rows[it] }
        assertEquals(5, measured)

        pixels()
        assertEquals(10, measured)
    }
}
