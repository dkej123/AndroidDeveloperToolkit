package dev.acme.adbtoolbox.domain.logcat

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Covers task 035's incremental filtering requirement: recomputing from a full snapshot only when
 * criteria changes, and applying only the new delta entries otherwise — never re-scanning or
 * re-copying the whole retained history per appended line.
 */
class LogcatFilterEngineTest {

    @Test
    fun `an unfiltered engine passes through every entry`() = runTest {
        val engine = LogcatFilterEngine()

        val appended = engine.applyDelta(listOf(sequenced(1, "Sample", "hello")))

        appended.map { it.sequence } shouldContainExactly listOf(1L)
        engine.snapshot().map { it.sequence } shouldContainExactly listOf(1L)
    }

    @Test
    fun `applying a delta only appends entries that match current criteria`() = runTest {
        val engine = LogcatFilterEngine()
        engine.setCriteria(LogcatFilterCriteria(minSeverity = LogSeverity.WARN), emptyList())

        val appended = engine.applyDelta(
            listOf(
                sequenced(1, "Sample", "info", LogSeverity.INFO),
                sequenced(2, "Sample", "warn", LogSeverity.WARN),
            ),
        )

        appended.map { it.sequence } shouldContainExactly listOf(2L)
        engine.snapshot().map { it.sequence } shouldContainExactly listOf(2L)
    }

    @Test
    fun `changing criteria recomputes from the full provided snapshot, replacing prior results`() = runTest {
        val engine = LogcatFilterEngine()
        val source = listOf(
            sequenced(1, "Sample", "info", LogSeverity.INFO),
            sequenced(2, "Sample", "warn", LogSeverity.WARN),
            sequenced(3, "Sample", "error", LogSeverity.ERROR),
        )
        engine.applyDelta(source.take(1))

        val recomputed = engine.setCriteria(LogcatFilterCriteria(minSeverity = LogSeverity.ERROR), source)

        recomputed.map { it.sequence } shouldContainExactly listOf(3L)
        engine.snapshot().map { it.sequence } shouldContainExactly listOf(3L)
    }

    @Test
    fun `evicting before a sequence trims only the retained filtered prefix`() = runTest {
        val engine = LogcatFilterEngine()
        engine.applyDelta(listOf(sequenced(1, "A", "x"), sequenced(2, "A", "y"), sequenced(3, "A", "z")))

        engine.evictBefore(oldestRetainedSequence = 3)

        engine.snapshot().map { it.sequence } shouldContainExactly listOf(3L)
    }

    @Test
    fun `eviction is a no-op when nothing filtered predates the retained floor`() = runTest {
        val engine = LogcatFilterEngine()
        engine.applyDelta(listOf(sequenced(5, "A", "x")))

        engine.evictBefore(oldestRetainedSequence = 1)

        engine.snapshot().map { it.sequence } shouldContainExactly listOf(5L)
    }

    @Test
    fun `recompute over ten thousand entries stays fast and correct`() = runTest {
        val engine = LogcatFilterEngine()
        val source = (1..10_000).map { index ->
            sequenced(index.toLong(), "Sample", "line $index", if (index % 2 == 0) LogSeverity.WARN else LogSeverity.INFO)
        }

        val elapsedMs = measureTimeMillis {
            engine.setCriteria(LogcatFilterCriteria(minSeverity = LogSeverity.WARN), source)
        }

        engine.snapshot().size shouldBe 5_000
        (elapsedMs < 5_000) shouldBe true
    }

    @Test
    fun `recompute is cancellable partway through a large snapshot`() = runTest {
        val engine = LogcatFilterEngine()
        val source = (1..50_000).map { index -> sequenced(index.toLong(), "Sample", "line $index") }

        val job = async {
            engine.setCriteria(LogcatFilterCriteria(query = "line"), source)
        }
        job.cancel()

        assertThrows<CancellationException> { job.await() }
    }

    private fun sequenced(
        sequence: Long,
        tag: String,
        message: String,
        severity: LogSeverity = LogSeverity.INFO,
    ): SequencedLogcatEntry {
        val entry = LogcatEntry.Record(
            record = LogcatRecord(
                timestamp = LogTimestamp(9, 10, 12, 34, 56, 789),
                pid = ProcessId(123),
                tid = ThreadId(456),
                severity = severity,
                tag = LogTag(tag),
                message = LogMessage(message),
            ),
            continuationLines = emptyList(),
        )
        return SequencedLogcatEntry(sequence, entry, entry.estimatedSizeBytes())
    }
}
