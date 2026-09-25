package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import javax.swing.JLabel

class LogcatVirtualListTest : BasePlatformTestCase() {

    fun `test body is a virtualized JBList and presentation changes preserve its model`() {
        val model = LogcatVirtualListModel { true }
        val list = LogcatVirtualList(model)

        list.presentation = LogcatRenderPresentation(
            wrapLines = true,
            columns = LogcatColumnVisibility(timestamp = false, tag = false),
        )

        assertSame(model, list.model)
        assertTrue(list.presentation.wrapLines)
        assertFalse(list.presentation.columns.timestamp)
        assertFalse(list.presentation.columns.tag)
    }

    fun `test cell text follows optional columns and wrap input`() {
        val model = LogcatVirtualListModel { true }
        val list = LogcatVirtualList(model)
        val row = LogcatRenderRow(
            1,
            LogSeverity.WARN,
            "09-10 12:34:56.789",
            "Sample",
            "a message",
        )
        model.apply(LogcatRenderBatch(listOf(row), reset = true))
        list.presentation = LogcatRenderPresentation(
            wrapLines = true,
            columns = LogcatColumnVisibility(timestamp = false, tag = true),
        )

        val component = list.cellRenderer.getListCellRendererComponent(list, row, 0, false, false) as JLabel

        assertFalse(component.text.contains("12:34"))
        assertTrue(component.text.contains("Sample"))
        assertTrue(component.text.startsWith("<html>"))
    }

    fun `test wrapping uses the available list width and increases row height for a long message`() {
        val model = LogcatVirtualListModel { true }
        val list = LogcatVirtualList(model)
        list.setSize(120, 400)
        val row = LogcatRenderRow(
            1,
            LogSeverity.INFO,
            null,
            null,
            "a very long message that must wrap across several visual lines in a narrow viewport",
        )
        model.apply(LogcatRenderBatch(listOf(row), reset = true))

        list.presentation = LogcatRenderPresentation(wrapLines = false)
        val unwrapped = list.cellRenderer.getListCellRendererComponent(list, row, 0, false, false).preferredSize.height
        list.presentation = LogcatRenderPresentation(wrapLines = true)
        val wrapped = list.cellRenderer.getListCellRendererComponent(list, row, 0, false, false).preferredSize.height

        assertTrue("wrapped=$wrapped, unwrapped=$unwrapped", wrapped > unwrapped)
    }

    fun `test unwrapped layout does not render every row yet stays wide enough for the longest one`() {
        // Regression (docs/e2e-testing.md): without a fixed cell width, every model change made
        // JList measure all rows, i.e. parse every row's HTML on the EDT; with a few thousand
        // buffered lines Android Studio's UI thread stayed at ~100% CPU.
        val model = LogcatVirtualListModel { true }
        val list = LogcatVirtualList(model)
        list.presentation = LogcatRenderPresentation(wrapLines = false)
        val longest = LogcatRenderRow(2_000, LogSeverity.INFO, "09-10 12:34:56.789", "Tag", "x".repeat(300))
        model.apply(LogcatRenderBatch((1L..1_999L).map { sequence ->
            LogcatRenderRow(sequence, LogSeverity.DEBUG, "09-10 12:34:56.789", "Tag", "message-$sequence")
        } + longest, reset = true))
        val renderer = list.cellRenderer
        var renderedRows = 0
        list.cellRenderer = javax.swing.ListCellRenderer { l, value, index, selected, focus ->
            renderedRows++
            renderer.getListCellRendererComponent(l, value, index, selected, focus)
        }

        val preferredWidth = list.preferredSize.width

        assertTrue("rendered $renderedRows rows to lay out", renderedRows < 50)
        val longestWidth = renderer.getListCellRendererComponent(list, longest, 1_999, false, false).preferredSize.width
        assertTrue("list width $preferredWidth < longest row $longestWidth", preferredWidth >= longestWidth)
    }

    fun `test repainting a row reuses its parsed HTML instead of parsing it again`() {
        // Regression (docs/e2e-testing.md): with autoscroll on, every appended batch repainted the
        // visible rows and each repaint re-parsed every row's HTML — ~80% of the UI thread.
        val model = LogcatVirtualListModel { true }
        val list = LogcatVirtualList(model)
        val row = LogcatRenderRow(1, LogSeverity.INFO, "09-10 12:34:56.789", "Tag", "message")
        model.apply(LogcatRenderBatch(listOf(row), reset = true))

        val first = (list.cellRenderer.getListCellRendererComponent(list, row, 0, false, false) as JLabel)
            .getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey)
        val second = (list.cellRenderer.getListCellRendererComponent(list, row, 0, false, false) as JLabel)
            .getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey)

        assertNotNull(first)
        assertSame(first, second)
    }

    fun `test re-applying an unchanged presentation does not invalidate every row`() {
        val model = LogcatVirtualListModel { true }
        val list = LogcatVirtualList(model)
        model.apply(LogcatRenderBatch((1L..3L).map { LogcatRenderRow(it, null, null, null, "m$it") }, reset = true))
        var changes = 0
        model.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) = Unit
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = Unit
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) { changes++ }
        })

        list.presentation = list.presentation.copy()

        assertEquals(0, changes)
    }

    fun `test head eviction preserves selection by stable sequence identity`() {
        val model = LogcatVirtualListModel { true }
        val list = LogcatVirtualList(model)
        model.apply(LogcatRenderBatch((1L..5L).map { sequence ->
            LogcatRenderRow(sequence, null, null, null, "message-$sequence")
        }, reset = true))
        list.selectedIndex = 3

        model.apply(LogcatRenderBatch(emptyList(), retainFromSequence = 3))

        assertEquals(4L, list.selectedValue.sequence)
    }
}
