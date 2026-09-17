package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme

/**
 * Task 048's pure Logcat presentation helpers (`design/README.md` §7's severity/search-hit tokens,
 * responsive tag column, and buffer footer) — kept independent of Swing painting so every supplied
 * severity/state/breakpoint is directly assertable.
 */
class LogcatRowStyleTest : BasePlatformTestCase() {

    fun `test level and message colors follow the supplied severity palette`() {
        assertSame(AdbToolboxTheme.LogSeverityColors.warn.level, LogcatRowStyle.levelColor(LogSeverity.WARN))
        assertSame(AdbToolboxTheme.LogSeverityColors.warn.message, LogcatRowStyle.messageColor(LogSeverity.WARN))
        assertSame(AdbToolboxTheme.LogSeverityColors.error.level, LogcatRowStyle.levelColor(LogSeverity.ERROR))
        assertSame(AdbToolboxTheme.LogSeverityColors.assert.level, LogcatRowStyle.levelColor(LogSeverity.ASSERT))
    }

    fun `test null severity falls back to the verbose palette`() {
        assertSame(AdbToolboxTheme.LogSeverityColors.verbose.level, LogcatRowStyle.levelColor(null))
        assertSame(AdbToolboxTheme.LogSeverityColors.verbose.message, LogcatRowStyle.messageColor(null))
    }

    fun `test only assert rows carry a row background tint and bold weight`() {
        assertSame(AdbToolboxTheme.LogSeverityColors.assert.rowBg, LogcatRowStyle.rowBackground(LogSeverity.ASSERT))
        assertTrue(LogcatRowStyle.isBoldRow(LogSeverity.ASSERT))
        LogSeverity.entries.filter { it != LogSeverity.ASSERT }.forEach { severity ->
            assertNull(LogcatRowStyle.rowBackground(severity))
            assertFalse(LogcatRowStyle.isBoldRow(severity))
        }
        assertNull(LogcatRowStyle.rowBackground(null))
        assertFalse(LogcatRowStyle.isBoldRow(null))
    }

    fun `test tag column is wide-breakpoint only, timestamp is dock-and-above`() {
        val dock = LogcatRowStyle.columnsFor(AdbToolboxTheme.Breakpoints.wide - 1)
        val wide = LogcatRowStyle.columnsFor(AdbToolboxTheme.Breakpoints.wide)

        assertFalse(dock.tag)
        assertTrue(dock.timestamp)
        assertTrue(wide.tag)
        assertTrue(wide.timestamp)
    }

    fun `test narrow width below the dock breakpoint hides both timestamp and tag`() {
        val justBelowNarrow = LogcatRowStyle.columnsFor(AdbToolboxTheme.Breakpoints.narrow - 1)
        val atNarrowIsDock = LogcatRowStyle.columnsFor(AdbToolboxTheme.Breakpoints.narrow)

        assertFalse(justBelowNarrow.timestamp)
        assertFalse(justBelowNarrow.tag)
        assertTrue(atNarrowIsDock.timestamp)
        assertFalse(atNarrowIsDock.tag)
    }

    fun `test the verbose chip is active for both null and explicit verbose min severity`() {
        assertTrue(LogcatRowStyle.isChipActive(LogSeverity.VERBOSE, null))
        assertTrue(LogcatRowStyle.isChipActive(LogSeverity.VERBOSE, LogSeverity.VERBOSE))
        assertFalse(LogcatRowStyle.isChipActive(LogSeverity.DEBUG, null))
        assertTrue(LogcatRowStyle.isChipActive(LogSeverity.ERROR, LogSeverity.ERROR))
        assertFalse(LogcatRowStyle.isChipActive(LogSeverity.ERROR, LogSeverity.WARN))
    }

    fun `test buffer size formats to whole megabytes`() {
        assertEquals("buffer 0 MB", LogcatRowStyle.formatBufferSize(0))
        assertEquals("buffer 16 MB", LogcatRowStyle.formatBufferSize(16 * 1024 * 1024))
        assertEquals("buffer 2 MB", LogcatRowStyle.formatBufferSize((1.6 * 1024 * 1024).toInt()))
    }

    fun `test long tags are truncated with an ellipsis, short tags pass through`() {
        assertEquals("Sample", LogcatRowStyle.truncateTag("Sample"))
        assertEquals("ExactlyEighteenCh…", LogcatRowStyle.truncateTag("ExactlyEighteenChars", maxChars = 18))
        assertEquals("ExactlyEighteenChar", LogcatRowStyle.truncateTag("ExactlyEighteenChar", maxChars = 19))
    }

    fun `test row html escapes text, hides optional columns, and highlights matched spans`() {
        val row = LogcatRenderRow(
            sequence = 1,
            severity = LogSeverity.WARN,
            timestamp = "09-10 12:34:56.789",
            tag = "OkHttp",
            message = "retry <timeout>",
            matchSpans = listOf(LogcatMatchSpan(6, 15)),
        )
        val hiddenColumns = LogcatRenderPresentation(columns = LogcatColumnVisibility(timestamp = false, tag = false))

        val html = LogcatRowStyle.rowHtml(row, hiddenColumns, wrapWidthPx = null)

        assertTrue(html.startsWith("<html>"))
        assertFalse(html.contains("12:34"))
        assertFalse(html.contains("OkHttp"))
        assertTrue(html.contains("&lt;timeout&gt;"))
        assertTrue(html.contains("background-color:"))
    }

    fun `test wrap width wraps the message in a fixed-width div and turns newlines into breaks`() {
        val row = LogcatRenderRow(1, LogSeverity.INFO, null, null, "line one\nline two")
        val presentation = LogcatRenderPresentation(wrapLines = true)

        val html = LogcatRowStyle.rowHtml(row, presentation, wrapWidthPx = 200)

        assertTrue(html.contains("width=\"200\""))
        assertTrue(html.contains("line one<br>line two"))
    }

    fun `test assert row messages render bold`() {
        val row = LogcatRenderRow(1, LogSeverity.ASSERT, null, null, "FATAL EXCEPTION")

        val html = LogcatRowStyle.rowHtml(row, LogcatRenderPresentation(), wrapWidthPx = null)

        assertTrue(html.contains("font-weight:bold"))
    }
}
