package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.logcat.LogSeverity

class LogcatVirtualListModelTest : BasePlatformTestCase() {

    fun `test reset establishes sorted rows and delta appends without replacing existing row identity`() {
        val model = LogcatVirtualListModel { true }
        val first = row(1)
        val second = row(2)
        model.apply(LogcatRenderBatch(listOf(second, first), reset = true))
        val retainedFirst = model.getElementAt(0)

        model.apply(LogcatRenderBatch(listOf(row(3))))

        assertEquals(3, model.size)
        assertSame(retainedFirst, model.getElementAt(0))
        assertEquals(listOf(1L, 2L, 3L), (0 until model.size).map { model.getElementAt(it).sequence })
    }

    fun `test update replaces the content for a stable sequence without inserting a duplicate`() {
        val model = LogcatVirtualListModel { true }
        model.apply(LogcatRenderBatch(listOf(row(1), row(2)), reset = true))
        val updated = row(2).copy(message = "updated", matchSpans = listOf(LogcatMatchSpan(0, 3)))

        model.apply(LogcatRenderBatch(listOf(updated)))

        assertEquals(2, model.size)
        assertEquals(updated, model.getElementAt(1))
    }

    fun `test retain floor evicts whole oldest rows before applying additions`() {
        val model = LogcatVirtualListModel { true }
        model.apply(LogcatRenderBatch((1L..5L).map(::row), reset = true))

        model.apply(LogcatRenderBatch(rows = listOf(row(6)), retainFromSequence = 4))

        assertEquals(listOf(4L, 5L, 6L), (0 until model.size).map { model.getElementAt(it).sequence })
    }

    fun `test rows below retain floor cannot be reintroduced by the same delta`() {
        val model = LogcatVirtualListModel { true }
        model.apply(LogcatRenderBatch((1L..5L).map(::row), reset = true))

        model.apply(LogcatRenderBatch(rows = listOf(row(2), row(6)), retainFromSequence = 4))

        assertEquals(listOf(4L, 5L, 6L), (0 until model.size).map { model.getElementAt(it).sequence })
    }

    fun `test match spans are defensively copied by a batch application`() {
        val spans = mutableListOf(LogcatMatchSpan(0, 2))
        val model = LogcatVirtualListModel { true }

        model.apply(LogcatRenderBatch(listOf(row(1).copy(matchSpans = spans)), reset = true))
        spans += LogcatMatchSpan(3, 5)

        assertEquals(listOf(LogcatMatchSpan(0, 2)), model.getElementAt(0).matchSpans)
    }

    fun `test mutation off EDT is rejected`() {
        val model = LogcatVirtualListModel { false }

        val failure = runCatching {
            model.apply(LogcatRenderBatch(listOf(row(1))))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("EDT"))
    }

    fun `test synthetic twelve thousand row batch remains ordered and addressable`() {
        val model = LogcatVirtualListModel { true }

        model.apply(LogcatRenderBatch((1L..12_000L).map(::row), reset = true))

        assertEquals(12_000, model.size)
        assertEquals(1L, model.getElementAt(0).sequence)
        assertEquals(12_000L, model.getElementAt(11_999).sequence)
    }

    fun `test large head eviction completes as one interval removal`() {
        val model = LogcatVirtualListModel { true }
        var removalEvents = 0
        model.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(event: javax.swing.event.ListDataEvent?) = Unit
            override fun contentsChanged(event: javax.swing.event.ListDataEvent?) = Unit
            override fun intervalRemoved(event: javax.swing.event.ListDataEvent?) {
                removalEvents++
            }
        })
        model.apply(LogcatRenderBatch((1L..12_000L).map(::row), reset = true))

        model.apply(LogcatRenderBatch(emptyList(), retainFromSequence = 10_001))

        assertEquals(2_000, model.size)
        assertEquals(1, removalEvents)
    }

    fun `test model has its own defensive row ceiling and retains the newest identities`() {
        val model = LogcatVirtualListModel(isEdt = { true }, maxRows = 3)

        model.apply(LogcatRenderBatch((1L..5L).map(::row), reset = true))
        model.apply(LogcatRenderBatch(listOf(row(6))))

        assertEquals(listOf(4L, 5L, 6L), (0 until model.size).map { model.getElementAt(it).sequence })
        assertEquals(3, model.droppedRows)
    }

    private fun row(sequence: Long) = LogcatRenderRow(
        sequence = sequence,
        severity = LogSeverity.INFO,
        timestamp = "09-10 12:34:56.789",
        tag = "Sample",
        message = "message-$sequence",
    )
}
