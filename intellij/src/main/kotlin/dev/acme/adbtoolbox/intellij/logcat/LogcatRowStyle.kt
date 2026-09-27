package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.ui.JBColor
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Task 048's pure Logcat row-presentation helpers (`design/README.md` §7: severity/search-hit
 * tokens, the wide-breakpoint tag column, the "V and above" default chip, and the footer's buffer
 * size) — kept independent from [javax.swing.JComponent] painting so every supplied
 * severity/state/breakpoint is unit-testable without a live display. [LogcatVirtualList]'s renderer
 * is the only caller; it never re-derives these mappings itself.
 */
object LogcatRowStyle {

    private val DATED_TIMESTAMP = Regex("""\d\d-\d\d\s+(\d\d:\d\d:\d\d\.\d{3})""")

    fun levelColor(severity: LogSeverity?): JBColor = paletteFor(severity).level
    fun messageColor(severity: LogSeverity?): JBColor = paletteFor(severity).message
    fun rowBackground(severity: LogSeverity?): JBColor? = paletteFor(severity).rowBg
    fun isBoldRow(severity: LogSeverity?): Boolean = severity == LogSeverity.ASSERT

    /** `design/README.md`'s responsive rule: the tag column shows only at `>= wide`; the timestamp
     * column shows at `dock` and `wide` (`>= narrow`) and is dropped in the `narrow` width class,
     * matching the supplied prototype's `showTime: W >= 340` (`design/designs/ADB Toolbox
     * Plugin.dc.html`). */
    fun columnsFor(width: Int): LogcatColumnVisibility =
        LogcatColumnVisibility(
            timestamp = width >= AdbToolboxTheme.Breakpoints.narrow,
            tag = width >= AdbToolboxTheme.Breakpoints.wide,
        )

    /** The supplied level-chip row has no separate "clear filter" chip: the V chip itself is the
     * "no floor" state, matching [dev.acme.adbtoolbox.application.logcat.LogcatControlsIntent.SetMinSeverity]'s
     * documented "`null` clears the floor" contract. */
    fun isChipActive(chipLevel: LogSeverity, minSeverity: LogSeverity?): Boolean =
        minSeverity == chipLevel || (chipLevel == LogSeverity.VERBOSE && minSeverity == null)

    /** Footer's "buffer 16 MB" (`design/README.md` §7), rounded to whole megabytes. */
    fun formatBufferSize(totalBytes: Int): String {
        val megabytes = (totalBytes / (1024.0 * 1024.0)).roundToInt()
        return "buffer $megabytes MB"
    }

    /** The tag column's 104px "ellipsised" treatment, approximated by character count — Swing's
     * basic HTML `text-overflow` support is unreliable, so [LogcatVirtualList]'s renderer needs the
     * substring already resolved before it ever reaches the label. */
    const val TAG_COLUMN_CHARS = 15

    fun truncateTag(tag: String, maxChars: Int = 18): String =
        if (tag.length <= maxChars) tag else tag.take(maxChars - 1) + "…"

    /** `threadtime`'s `MM-DD HH:MM:SS.mmm` shown without the date: within a session it is noise that costs 6 columns. */
    fun displayTimestamp(raw: String): String = DATED_TIMESTAMP.matchEntire(raw)?.groupValues?.get(1) ?: raw

    /**
     * The fully-styled HTML body for one row's cell renderer text: level letter, optional
     * timestamp/tag columns per [presentation]'s [LogcatColumnVisibility], and the message with its
     * search-match [LogcatRenderRow.matchSpans] highlighted. With [wrapCells] the text is broken
     * by hand every [wrapCells] monospace cells and at newlines, so each row is exactly
     * [wrappedLineCount] lines tall and Swing never has to measure or re-wrap it; `null` renders
     * unwrapped (single line, newlines rendered as spaces).
     */
    fun rowHtml(row: LogcatRenderRow, presentation: LogcatRenderPresentation, wrapCells: Int?): String {
        val runs = runsOf(row, presentation)
        if (wrapCells == null || !presentation.wrapLines) {
            return "<html>" + runs.joinToString("") { it.html(it.text.replace('\n', ' ')) } + "</html>"
        }
        val lines = wrapRuns(runs, wrapCells.coerceAtLeast(1))
        return "<html>" + lines.joinToString("<br>") { line -> line.joinToString("") { it.html(it.text) } } + "</html>"
    }

    /** How many lines [rowHtml] produces for [row] at [cellsPerLine] — pure arithmetic, no HTML. */
    fun wrappedLineCount(row: LogcatRenderRow, presentation: LogcatRenderPresentation, cellsPerLine: Int): Int {
        val width = cellsPerLine.coerceAtLeast(1)
        var lines = 0
        var prefix = prefixCells(row, presentation)
        row.message.split('\n').forEach { segment ->
            val cells = prefix + segment.length
            lines += maxOf(1, (cells + width - 1) / width)
            prefix = 0
        }
        return lines
    }

    /** Upper bound of an unwrapped row's width in monospace cells: [rowHtml]'s visible text with
     * every optional column shown and one-cell gaps between segments. */
    fun unwrappedCells(row: LogcatRenderRow): Int {
        var cells = row.message.length
        if (row.severity != null) cells += 2
        row.timestamp?.let { cells += displayTimestamp(it).length + 1 }
        if (row.tag != null) cells += TAG_COLUMN_CHARS + 1
        return cells
    }

    /** One styled stretch of a row's visible text; [text] is plain (unescaped). */
    private data class Run(val text: String, val color: Color, val bold: Boolean, val hit: Boolean = false) {
        fun html(visible: String): String {
            // Non-breaking spaces keep monospace columns aligned (HTML collapses runs of spaces).
            val escaped = visible.htmlEscaped().replace(" ", "&nbsp;")
            val weight = if (bold) ";font-weight:bold" else ""
            val background = if (hit) ";background-color:${colorHex(searchHitBackground())}" else ""
            return "<span style='color:${colorHex(color)}$weight$background'>$escaped</span>"
        }
    }

    private fun prefixCells(row: LogcatRenderRow, presentation: LogcatRenderPresentation): Int {
        var cells = 0
        if (row.severity != null) cells += 2
        if (presentation.columns.timestamp) row.timestamp?.let { cells += displayTimestamp(it).length + 1 }
        if (presentation.columns.tag && row.tag != null) cells += TAG_COLUMN_CHARS + 1
        return cells
    }

    private fun runsOf(row: LogcatRenderRow, presentation: LogcatRenderPresentation): List<Run> = buildList {
        val gap = Run(" ", AdbToolboxTheme.LogSeverityColors.timestamp, bold = false)
        row.severity?.let { severity ->
            add(Run(severity.name.first().toString(), levelColor(severity), bold = isBoldRow(severity)))
            add(gap)
        }
        if (presentation.columns.timestamp) {
            row.timestamp?.let {
                add(Run(displayTimestamp(it), AdbToolboxTheme.LogSeverityColors.timestamp, bold = false))
                add(gap)
            }
        }
        if (presentation.columns.tag) {
            row.tag?.let { tag ->
                // Fixed-width mono column (`tagStyle`: 104px ≈ 15 cells of the 11px editor font), so
                // messages start on one vertical edge instead of right after each tag.
                add(Run(truncateTag(tag, TAG_COLUMN_CHARS).padEnd(TAG_COLUMN_CHARS), AdbToolboxTheme.LogSeverityColors.tag, bold = false))
                add(gap)
            }
        }
        val color = messageColor(row.severity)
        val bold = isBoldRow(row.severity)
        var cursor = 0
        row.matchSpans.sortedBy { it.startInclusive }.forEach { span ->
            val start = span.startInclusive.coerceIn(cursor, row.message.length)
            val end = span.endExclusive.coerceIn(start, row.message.length)
            if (start > cursor) add(Run(row.message.substring(cursor, start), color, bold))
            if (end > start) add(Run(row.message.substring(start, end), color, bold, hit = true))
            cursor = end
        }
        if (cursor < row.message.length || row.message.isEmpty()) add(Run(row.message.substring(cursor), color, bold))
    }

    /** Splits [runs] into lines of at most [width] cells, also breaking at every newline. */
    private fun wrapRuns(runs: List<Run>, width: Int): List<List<Run>> {
        val lines = mutableListOf<MutableList<Run>>(mutableListOf())
        var used = 0
        runs.forEach { run ->
            var rest = run.text
            while (true) {
                val newline = rest.indexOf('\n')
                val piece = if (newline >= 0) rest.substring(0, newline) else rest
                var remaining = piece
                while (remaining.isNotEmpty()) {
                    if (used == width) {
                        lines += mutableListOf<Run>()
                        used = 0
                    }
                    val take = minOf(width - used, remaining.length)
                    lines.last() += run.copy(text = remaining.substring(0, take))
                    used += take
                    remaining = remaining.substring(take)
                }
                if (newline < 0) break
                lines += mutableListOf<Run>()
                used = 0
                rest = rest.substring(newline + 1)
            }
        }
        return lines
    }

    /** A search-hit background solid enough for Swing's basic HTML `background-color` (which does
     * not reliably parse `rgba()`), blended from the supplied translucent token
     * ([AdbToolboxTheme.LogSeverityColors.searchHit]) over the Logcat body background. */
    private fun searchHitBackground(): Color =
        blendOverBackground(AdbToolboxTheme.LogSeverityColors.searchHit, AdbToolboxTheme.Colors.bg)

    internal fun blendOverBackground(overlay: Color, background: Color): Color {
        val overlayRgb = overlay.rgb
        val backgroundRgb = background.rgb
        val alpha = ((overlayRgb ushr 24) and 0xff) / 255.0
        fun channel(shift: Int): Int {
            val o = (overlayRgb ushr shift) and 0xff
            val b = (backgroundRgb ushr shift) and 0xff
            return ((o * alpha) + (b * (1 - alpha))).roundToInt().coerceIn(0, 255)
        }
        return Color(channel(16), channel(8), channel(0))
    }

    private fun colorHex(color: Color): String = "#%06X".format(color.rgb and 0xFFFFFF)

    private data class Palette(val level: JBColor, val message: JBColor, val rowBg: JBColor? = null)

    private fun paletteFor(severity: LogSeverity?): Palette = when (severity) {
        null, LogSeverity.VERBOSE -> Palette(
            AdbToolboxTheme.LogSeverityColors.verbose.level,
            AdbToolboxTheme.LogSeverityColors.verbose.message,
        )
        LogSeverity.DEBUG -> Palette(AdbToolboxTheme.LogSeverityColors.debug.level, AdbToolboxTheme.LogSeverityColors.debug.message)
        LogSeverity.INFO -> Palette(AdbToolboxTheme.LogSeverityColors.info.level, AdbToolboxTheme.LogSeverityColors.info.message)
        LogSeverity.WARN -> Palette(AdbToolboxTheme.LogSeverityColors.warn.level, AdbToolboxTheme.LogSeverityColors.warn.message)
        LogSeverity.ERROR -> Palette(AdbToolboxTheme.LogSeverityColors.error.level, AdbToolboxTheme.LogSeverityColors.error.message)
        LogSeverity.ASSERT -> Palette(
            AdbToolboxTheme.LogSeverityColors.assert.level,
            AdbToolboxTheme.LogSeverityColors.assert.message,
            AdbToolboxTheme.LogSeverityColors.assert.rowBg,
        )
    }
}

internal fun String.htmlEscaped(): String = buildString(length) {
    this@htmlEscaped.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                else -> character
            },
        )
    }
}
