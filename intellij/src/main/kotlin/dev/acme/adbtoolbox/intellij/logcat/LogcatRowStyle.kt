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

    fun levelColor(severity: LogSeverity?): JBColor = paletteFor(severity).level
    fun messageColor(severity: LogSeverity?): JBColor = paletteFor(severity).message
    fun rowBackground(severity: LogSeverity?): JBColor? = paletteFor(severity).rowBg
    fun isBoldRow(severity: LogSeverity?): Boolean = severity == LogSeverity.ASSERT

    /** `design/README.md`'s responsive rule: Logcat shows the tag column only at `>= wide`; the
     * timestamp column is not width-gated. */
    fun columnsFor(width: Int): LogcatColumnVisibility =
        LogcatColumnVisibility(timestamp = true, tag = width >= AdbToolboxTheme.Breakpoints.wide)

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
    fun truncateTag(tag: String, maxChars: Int = 18): String =
        if (tag.length <= maxChars) tag else tag.take(maxChars - 1) + "…"

    /**
     * The fully-styled HTML body for one row's cell renderer text: level letter, optional
     * timestamp/tag columns per [presentation]'s [LogcatColumnVisibility], and the message with its
     * search-match [LogcatRenderRow.matchSpans] highlighted. [wrapWidthPx] mirrors the existing
     * "wrap uses the available list width" behavior; `null` renders unwrapped (single line,
     * newlines rendered as spaces).
     */
    fun rowHtml(row: LogcatRenderRow, presentation: LogcatRenderPresentation, wrapWidthPx: Int?): String {
        val segments = mutableListOf<String>()
        row.severity?.let { severity ->
            segments += htmlSpan(severity.name.first().toString(), levelColor(severity), bold = isBoldRow(severity))
        }
        if (presentation.columns.timestamp) {
            row.timestamp?.let { segments += htmlSpan(it, AdbToolboxTheme.LogSeverityColors.timestamp) }
        }
        if (presentation.columns.tag) {
            row.tag?.let { segments += htmlSpan(truncateTag(it), AdbToolboxTheme.LogSeverityColors.tag) }
        }
        segments += messageHtml(row, presentation.wrapLines)

        val joined = segments.joinToString("&nbsp;&nbsp;")
        val body = if (wrapWidthPx != null) "<div width=\"$wrapWidthPx\">$joined</div>" else joined
        return "<html>$body</html>"
    }

    private fun messageHtml(row: LogcatRenderRow, wrapLines: Boolean): String {
        val highlighted = highlightedEscaped(row.message, row.matchSpans)
        val withLineBreaks = if (wrapLines) highlighted.replace("\n", "<br>") else highlighted.replace("\n", " ")
        return htmlSpanRaw(withLineBreaks, messageColor(row.severity), bold = isBoldRow(row.severity))
    }

    private fun highlightedEscaped(text: String, spans: List<LogcatMatchSpan>): String {
        if (spans.isEmpty()) return text.htmlEscaped()
        val sorted = spans.sortedBy { it.startInclusive }
        val hitColor = colorHex(searchHitBackground())
        val builder = StringBuilder()
        var cursor = 0
        sorted.forEach { span ->
            builder.append(text.substring(cursor, span.startInclusive).htmlEscaped())
            builder.append("<span style='background-color:$hitColor'>")
            builder.append(text.substring(span.startInclusive, span.endExclusive).htmlEscaped())
            builder.append("</span>")
            cursor = span.endExclusive
        }
        builder.append(text.substring(cursor).htmlEscaped())
        return builder.toString()
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

    private fun htmlSpan(text: String, color: Color, bold: Boolean = false): String =
        htmlSpanRaw(text.htmlEscaped(), color, bold)

    private fun htmlSpanRaw(rawHtml: String, color: Color, bold: Boolean): String {
        val weight = if (bold) ";font-weight:bold" else ""
        return "<span style='color:${colorHex(color)}$weight'>$rawHtml</span>"
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
