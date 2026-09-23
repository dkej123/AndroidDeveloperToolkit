package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JLabel
import javax.swing.JList

/** Task 048's final visual design for the Logcat body (`design/README.md` §7): severity-colored
 * level/message text, dim timestamp/tag columns, the assert row tint, and search-hit highlighting,
 * all computed by [LogcatRowStyle] so this renderer stays a thin Swing adapter over pure logic. */
class LogcatVirtualList(
    val virtualModel: LogcatVirtualListModel = LogcatVirtualListModel(),
) : JBList<LogcatRenderRow>(virtualModel) {
    var presentation: LogcatRenderPresentation = LogcatRenderPresentation()
        set(value) {
            field = value
            applyRowHeight()
            virtualModel.presentationChanged()
            revalidate()
            repaint()
        }

    init {
        background = AdbToolboxTheme.Colors.bg
        // `logBodyStyle`: `padding: 4px 0 6px`.
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s2, 0, AdbToolboxTheme.Spacing.s3, 0)
        cellRenderer = RowRenderer { presentation }
        applyRowHeight()
    }

    /** Unwrapped rows use the fixed 16px log line height; wrapped rows measure their own height. */
    private fun applyRowHeight() {
        fixedCellHeight = if (presentation.wrapLines) -1 else AdbToolboxTheme.Sizes.logLineHeight
    }

    private class RowRenderer(
        private val presentation: () -> LogcatRenderPresentation,
    ) : DefaultListCellRenderer() {
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
            component.font = AdbToolboxTheme.Typography.mono
            // `rowStyle`: `padding: 0 8px`.
            component.border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s4)

            val wrapWidthPx = if (options.wrapLines) {
                list?.let { (it.width - it.insets.left - it.insets.right - AdbToolboxTheme.Spacing.s4 * 2).coerceAtLeast(1) }
            } else {
                null
            }
            component.text = LogcatRowStyle.rowHtml(row, options, wrapWidthPx)

            component.isOpaque = true
            component.background = when {
                isSelected -> AdbToolboxTheme.Colors.accentBg
                else -> LogcatRowStyle.rowBackground(row.severity) ?: AdbToolboxTheme.Colors.bg
            }
            return component
        }
    }
}
