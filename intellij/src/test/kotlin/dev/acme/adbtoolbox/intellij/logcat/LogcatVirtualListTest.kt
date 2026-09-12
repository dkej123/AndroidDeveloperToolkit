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
