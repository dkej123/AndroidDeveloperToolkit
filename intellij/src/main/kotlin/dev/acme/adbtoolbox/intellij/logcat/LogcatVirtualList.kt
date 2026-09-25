package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.plaf.basic.BasicHTML
import javax.swing.text.View
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

/** Task 048's final visual design for the Logcat body (`design/README.md` §7): severity-colored
 * level/message text, dim timestamp/tag columns, the assert row tint, and search-hit highlighting,
 * all computed by [LogcatRowStyle] so this renderer stays a thin Swing adapter over pure logic. */
class LogcatVirtualList(
    val virtualModel: LogcatVirtualListModel = LogcatVirtualListModel(),
) : JBList<LogcatRenderRow>(virtualModel) {
    var presentation: LogcatRenderPresentation = LogcatRenderPresentation()
        set(value) {
            if (value == field) return
            field = value
            applyRowHeight()
            applyRowWidth()
            virtualModel.presentationChanged()
            revalidate()
            repaint()
        }

    init {
        background = AdbToolboxTheme.Colors.bg
        // `logBodyStyle`: `padding: 4px 0 6px`.
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s2, 0, AdbToolboxTheme.Spacing.s3, 0)
        cellRenderer = RowRenderer { presentation }
        virtualModel.addListDataListener(WidestRowTracker())
        applyRowHeight()
        applyRowWidth()
    }

    /** Longest row seen since the model was last emptied, in monospace cells. */
    private var widestRowCells = 0

    /** Unwrapped rows use the fixed 16px log line height; wrapped rows measure their own height. */
    private fun applyRowHeight() {
        fixedCellHeight = if (presentation.wrapLines) -1 else AdbToolboxTheme.Sizes.logLineHeight
    }

    /**
     * Without a fixed cell width, every model change makes the list UI measure every row — i.e.
     * build and lay out each row's HTML on the EDT, O(buffer) per appended batch. Unwrapped rows are
     * a single line of a monospace font, so the widest row is known from its character count alone.
     * Wrapped rows fill the viewport width and are measured by Swing as before.
     */
    private fun applyRowWidth() {
        if (presentation.wrapLines) {
            fixedCellWidth = -1
            return
        }
        val cellWidth = getFontMetrics(AdbToolboxTheme.Typography.mono).charWidth('m')
        fixedCellWidth = (widestRowCells + ROW_WIDTH_SLACK_CELLS) * cellWidth + AdbToolboxTheme.Spacing.s4 * 2
    }

    private inner class WidestRowTracker : ListDataListener {
        override fun intervalAdded(e: ListDataEvent) = widen(e.index0, e.index1)
        override fun contentsChanged(e: ListDataEvent) = widen(e.index0, e.index1)
        override fun intervalRemoved(e: ListDataEvent) {
            if (virtualModel.size == 0 && widestRowCells != 0) {
                widestRowCells = 0
                applyRowWidth()
            }
        }

        private fun widen(from: Int, to: Int) {
            if (from < 0) return
            var widest = widestRowCells
            for (index in from..minOf(to, virtualModel.size - 1)) {
                widest = maxOf(widest, LogcatRowStyle.unwrappedCells(virtualModel.getElementAt(index)))
            }
            if (widest != widestRowCells) {
                widestRowCells = widest
                applyRowWidth()
            }
        }
    }

    private class RowRenderer(
        private val presentation: () -> LogcatRenderPresentation,
    ) : DefaultListCellRenderer() {

        /**
         * Parsed row HTML by markup, most recently used last. Swing re-parses a label's HTML on
         * every text/font/foreground change; with autoscroll each appended batch repaints the
         * visible rows, so without this cache the UI thread spent most of its time parsing.
         */
        private val htmlViews = object : LinkedHashMap<String, View>(HTML_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, View>?) = size > HTML_CACHE_SIZE
        }

        private var cachedFont: java.awt.Font? = null

        /** The HTML view is managed by [setHtml]; these changes must not trigger Swing's re-parse. */
        override fun firePropertyChange(propertyName: String?, oldValue: Any?, newValue: Any?) {
            if (propertyName == "text" || propertyName == "font" || propertyName == "foreground") return
            super.firePropertyChange(propertyName, oldValue, newValue)
        }

        fun setHtml(component: JLabel, html: String) {
            component.text = html
            val view = htmlViews.getOrPut(html) { BasicHTML.createHTMLView(component, html) }
            component.putClientProperty(BasicHTML.propertyKey, view)
        }

        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            val row = value as? LogcatRenderRow ?: return component
            val options = presentation()
            val mono = AdbToolboxTheme.Typography.mono
            component.font = mono
            if (mono != cachedFont) {
                // Parsed views bake in the font; a theme or zoom change invalidates them.
                htmlViews.clear()
                cachedFont = mono
            }
            // `rowStyle`: `padding: 0 8px`.
            component.border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s4)

            val wrapWidthPx = if (options.wrapLines) {
                list?.let { (it.width - it.insets.left - it.insets.right - AdbToolboxTheme.Spacing.s4 * 2).coerceAtLeast(1) }
            } else {
                null
            }
            setHtml(component, LogcatRowStyle.rowHtml(row, options, wrapWidthPx))

            component.isOpaque = true
            component.background = when {
                isSelected -> AdbToolboxTheme.Colors.accentBg
                else -> LogcatRowStyle.rowBackground(row.severity) ?: AdbToolboxTheme.Colors.bg
            }
            return component
        }
    }
}

/** Covers bold (assert) rows and font-metric rounding in [LogcatVirtualList]'s width estimate. */
private const val ROW_WIDTH_SLACK_CELLS = 2

/** Enough parsed rows for several screens of scrolling back and forth. */
private const val HTML_CACHE_SIZE = 1024
