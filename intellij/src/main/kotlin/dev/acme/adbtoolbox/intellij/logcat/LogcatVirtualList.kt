package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.ui.components.JBList
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/** Task 036's virtualized Logcat body; final visual styling belongs to task 048. */
class LogcatVirtualList(
    val virtualModel: LogcatVirtualListModel = LogcatVirtualListModel(),
) : JBList<LogcatRenderRow>(virtualModel) {
    var presentation: LogcatRenderPresentation = LogcatRenderPresentation()
        set(value) {
            field = value
            virtualModel.presentationChanged()
            revalidate()
            repaint()
        }

    init {
        cellRenderer = RowRenderer { presentation }
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
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val row = value as? LogcatRenderRow ?: return component
            val options = presentation()
            val parts = buildList {
                row.severity?.let { add(it.name.first().toString()) }
                if (options.columns.timestamp) row.timestamp?.let(::add)
                if (options.columns.tag) row.tag?.let(::add)
                add(row.message)
            }
            text = parts.joinToString("  ")
            if (options.wrapLines) {
                val availableWidth = list?.width?.coerceAtLeast(1) ?: 1
                text = "<html><div width=\"$availableWidth\">${text.htmlEscaped().replace("\n", "<br>")}</div></html>"
            }
            return component
        }
    }
}

private fun String.htmlEscaped(): String = buildString(length) {
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
