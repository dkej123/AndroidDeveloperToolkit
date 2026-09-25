package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.concurrent.thread

private class QueuedLogcatEdtScheduler : LogcatEdtScheduler {
    private data class Scheduled(val task: () -> Unit, var cancelled: Boolean = false)

    private val tasks = ArrayDeque<Scheduled>()
    val queuedCount: Int get() = tasks.size
    var cancelledCount: Int = 0

    override fun schedule(task: () -> Unit): LogcatScheduledTask {
        val scheduled = Scheduled(task)
        tasks.addLast(scheduled)
        return LogcatScheduledTask {
            if (!scheduled.cancelled) {
                scheduled.cancelled = true
                cancelledCount++
            }
        }
    }

    fun runNext() = tasks.removeFirst().let { if (!it.cancelled) it.task() }

    fun runAll() {
        while (tasks.isNotEmpty()) runNext()
    }
}

class LogcatEdtBatcherTest : BasePlatformTestCase() {

    fun `test a drain that holds the EDT past the threshold is logged with its row count`() {
        val scheduler = QueuedLogcatEdtScheduler()
        val log = dev.acme.adbtoolbox.domain.diagnostics.RecordingDiagnosticsLog()
        var now = 0L
        val batcher = LogcatEdtBatcher(
            LogcatVirtualListModel { true },
            scheduler,
            log = log,
            nanoTime = { now.also { now += 80_000_000 } },
        )

        repeat(3) { batcher.submit(LogcatRenderBatch(listOf(row(it.toLong() + 1)))) }
        scheduler.runAll()

        val entry = log.inCategory(dev.acme.adbtoolbox.domain.diagnostics.DiagCategory.LOGCAT).single { it.message == "slow drain" }
        assertEquals(dev.acme.adbtoolbox.domain.diagnostics.DiagLevel.WARN, entry.level)
        assertEquals(3, entry.fields["rows"])
        assertEquals(80L, entry.fields["ms"])
    }

    fun `test ten thousand single-row submissions queue one task initially and drain in bounded chunks`() {
        val scheduler = QueuedLogcatEdtScheduler()
        val model = LogcatVirtualListModel { true }
        val batcher = LogcatEdtBatcher(model, scheduler, maxPendingRows = 20_000)

        repeat(10_000) { batcher.submit(LogcatRenderBatch(listOf(row(it.toLong() + 1)))) }

        assertEquals(1, scheduler.queuedCount)
        assertEquals(1, batcher.metrics.scheduledEdtTasks)
        assertEquals(10_000, batcher.metrics.peakPendingRows)

        scheduler.runAll()

        assertEquals(10_000, model.size)
        assertEquals(10, batcher.metrics.appliedBatches)
        assertEquals(1_000, batcher.metrics.peakAppliedRows)
        assertEquals(9_999, batcher.metrics.coalescedBatches)
    }

    fun `test pending row ceiling evicts oldest work and reports the drop`() {
        val scheduler = QueuedLogcatEdtScheduler()
        val model = LogcatVirtualListModel { true }
        val batcher = LogcatEdtBatcher(model, scheduler, maxPendingRows = 100)

        repeat(1_000) { batcher.submit(LogcatRenderBatch(listOf(row(it.toLong() + 1)))) }
        scheduler.runNext()

        assertEquals(100, model.size)
        assertEquals(901L, model.getElementAt(0).sequence)
        assertEquals(900, batcher.metrics.droppedPendingRows)
    }

    fun `test a reset supersedes older pending deltas`() {
        val scheduler = QueuedLogcatEdtScheduler()
        val model = LogcatVirtualListModel { true }
        val batcher = LogcatEdtBatcher(model, scheduler)
        batcher.submit(LogcatRenderBatch(listOf(row(1), row(2))))

        batcher.submit(LogcatRenderBatch(listOf(row(10)), reset = true))
        scheduler.runNext()

        assertEquals(1, model.size)
        assertEquals(10L, model.getElementAt(0).sequence)
    }

    fun `test disposal clears pending rows and scheduled work becomes a safe no-op`() {
        val scheduler = QueuedLogcatEdtScheduler()
        val model = LogcatVirtualListModel { true }
        val batcher = LogcatEdtBatcher(model, scheduler)
        batcher.submit(LogcatRenderBatch(listOf(row(1))))

        batcher.dispose()
        assertEquals(1, scheduler.cancelledCount)
        scheduler.runNext()
        batcher.submit(LogcatRenderBatch(listOf(row(2))))

        assertEquals(0, model.size)
        assertEquals(1, batcher.metrics.ignoredAfterDispose)
    }

    fun `test coalesced retain floors only keep rows at or above the newest floor`() {
        val scheduler = QueuedLogcatEdtScheduler()
        val model = LogcatVirtualListModel { true }
        val batcher = LogcatEdtBatcher(model, scheduler)
        batcher.submit(LogcatRenderBatch((1L..10L).map(::row), reset = true))
        batcher.submit(LogcatRenderBatch(listOf(row(3), row(11)), retainFromSequence = 6))
        batcher.submit(LogcatRenderBatch(listOf(row(5), row(12)), retainFromSequence = 8))

        scheduler.runNext()

        assertEquals((8L..12L).toList(), (0 until model.size).map { model.getElementAt(it).sequence })
    }

    fun `test concurrent producers still schedule one bounded EDT drain`() {
        val scheduler = QueuedLogcatEdtScheduler()
        val model = LogcatVirtualListModel { true }
        val batcher = LogcatEdtBatcher(model, scheduler, maxPendingRows = 10_000)

        val producers = (0 until 8).map { producer ->
            thread {
                repeat(1_000) { offset ->
                    val sequence = producer * 1_000L + offset + 1
                    batcher.submit(LogcatRenderBatch(listOf(row(sequence))))
                }
            }
        }
        producers.forEach(Thread::join)

        assertEquals(1, scheduler.queuedCount)
        assertEquals(8_000, batcher.metrics.submittedBatches)
        scheduler.runAll()
        assertEquals(8_000, model.size)
        assertEquals(1L, model.getElementAt(0).sequence)
        assertEquals(8_000L, model.getElementAt(model.size - 1).sequence)
    }

    private fun row(sequence: Long) = LogcatRenderRow(
        sequence = sequence,
        severity = null,
        timestamp = null,
        tag = null,
        message = "message-$sequence",
    )
}
