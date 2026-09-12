@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.domain.logcat

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LogcatBufferTest {

    @Test
    fun `default buffer capacity is the specified sixteen mebibytes`() {
        LogcatBuffer().capacityBytes shouldBe 16 * 1024 * 1024
    }

    @Test
    fun `whole oldest entries are evicted at the byte boundary and ordering is retained`() = runTest {
        val first = malformed("first")
        val second = malformed("second")
        val third = malformed("third")
        val capacity = first.estimatedSizeBytes() + second.estimatedSizeBytes()
        val buffer = LogcatBuffer(capacityBytes = capacity, entryCeilingBytes = capacity)

        buffer.append(first)
        buffer.append(second)
        buffer.snapshot().entries.map { it.entry }.shouldContainExactly(first, second)

        buffer.append(third)

        val snapshot = buffer.snapshot()
        snapshot.entries.map { it.entry }.shouldContainExactly(second, third)
        snapshot.totalBytes shouldBe second.estimatedSizeBytes() + third.estimatedSizeBytes()
        snapshot.entries.map { it.sequence }.shouldContainExactly(2L, 3L)
    }

    @Test
    fun `entry exactly at capacity is retained`() = runTest {
        val entry = malformed("fits exactly")
        val size = entry.estimatedSizeBytes()
        val buffer = LogcatBuffer(capacityBytes = size, entryCeilingBytes = size)

        buffer.append(entry).shouldBeInstanceOf<LogcatAppendResult.Added>()

        buffer.snapshot().entries.single().entry shouldBe entry
        buffer.snapshot().totalBytes shouldBe size
    }

    @Test
    fun `oversize entry is rejected without evicting retained history or consuming a sequence`() = runTest {
        val retained = malformed("retained")
        val oversize = malformed("x".repeat(128))
        val buffer = LogcatBuffer(capacityBytes = 512, entryCeilingBytes = 64)
        buffer.append(retained)

        buffer.append(oversize) shouldBe
            LogcatAppendResult.RejectedOversize(oversize.estimatedSizeBytes(), 64)
        val next = buffer.append(malformed("next")).shouldBeInstanceOf<LogcatAppendResult.Added>()

        next.entry.sequence shouldBe 2L
        buffer.snapshot().entries.map { it.entry }.shouldContainExactly(retained, malformed("next"))
    }

    @Test
    fun `clear is local, keeps sequence monotonic, and invalidates an older cursor`() = runTest {
        val buffer = LogcatBuffer(capacityBytes = 1_024, entryCeilingBytes = 1_024)
        buffer.append(malformed("before"))
        val oldCursor = buffer.snapshot().cursor

        val cleared = buffer.clear()
        val after = buffer.append(malformed("after")).shouldBeInstanceOf<LogcatAppendResult.Added>()
        val delta = buffer.deltaAfter(oldCursor)

        cleared.entries shouldBe emptyList()
        cleared.totalBytes shouldBe 0
        after.entry.sequence shouldBe 2L
        delta.resetRequired shouldBe true
        delta.entries.map { it.entry }.shouldContainExactly(malformed("after"))
        delta.cursor.generation shouldBe oldCursor.generation + 1
    }

    @Test
    fun `delta reports a gap when its cursor predates an evicted entry`() = runTest {
        val first = malformed("one")
        val second = malformed("two")
        val third = malformed("three")
        val capacity = second.estimatedSizeBytes() + third.estimatedSizeBytes()
        val buffer = LogcatBuffer(capacityBytes = capacity, entryCeilingBytes = capacity)
        val cursor = buffer.snapshot().cursor
        buffer.append(first)
        buffer.append(second)
        buffer.append(third)

        val delta = buffer.deltaAfter(cursor)

        delta.resetRequired shouldBe true
        delta.entries.map { it.entry }.shouldContainExactly(second, third)
    }

    @Test
    fun `delta after a retained cursor contains only newer immutable entries`() = runTest {
        val buffer = LogcatBuffer(capacityBytes = 1_024, entryCeilingBytes = 1_024)
        buffer.append(malformed("one"))
        val cursor = buffer.snapshot().cursor
        buffer.append(malformed("two"))
        buffer.append(malformed("three"))

        val delta = buffer.deltaAfter(cursor)

        delta.resetRequired shouldBe false
        delta.entries.map { it.entry }.shouldContainExactly(malformed("two"), malformed("three"))
    }

    @Test
    fun `delta exposes the oldest retained sequence when eviction happened before a valid cursor`() = runTest {
        val entry = malformed("same-size")
        val capacity = entry.estimatedSizeBytes() * 2
        val buffer = LogcatBuffer(capacityBytes = capacity, entryCeilingBytes = capacity)
        buffer.append(entry)
        buffer.append(entry)
        val cursor = buffer.snapshot().cursor

        buffer.append(entry)
        val delta = buffer.deltaAfter(cursor)

        delta.resetRequired shouldBe false
        delta.oldestRetainedSequence shouldBe 2L
    }

    @Test
    fun `record continuation list is defensively copied on append`() = runTest {
        val continuations = mutableListOf("first frame")
        val entry = record("message", continuations)
        val buffer = LogcatBuffer(capacityBytes = 1_024, entryCeilingBytes = 1_024)

        buffer.append(entry)
        continuations += "mutated later"

        val stored = buffer.snapshot().entries.single().entry.shouldBeInstanceOf<LogcatEntry.Record>()
        stored.continuationLines.shouldContainExactly("first frame")
    }

    @Test
    fun `concurrent appends produce unique ordered sequences and remain bounded`() = runTest {
        val sample = malformed("concurrent")
        val capacity = sample.estimatedSizeBytes() * 50
        val buffer = LogcatBuffer(capacityBytes = capacity, entryCeilingBytes = sample.estimatedSizeBytes())

        (1..1_000).map { index -> async { buffer.append(malformed("concurrent-$index")) } }.awaitAll()

        val snapshot = buffer.snapshot()
        snapshot.entries.zipWithNext().all { (a, b) -> a.sequence < b.sequence } shouldBe true
        snapshot.entries.map { it.sequence }.distinct().size shouldBe snapshot.entries.size
        (snapshot.totalBytes <= capacity) shouldBe true
    }

    @Test
    fun `ten thousand entries keep only bounded whole entries`() = runTest {
        val sample = malformed("stress")
        val capacity = sample.estimatedSizeBytes() * 128
        val buffer = LogcatBuffer(capacityBytes = capacity, entryCeilingBytes = sample.estimatedSizeBytes() + 16)

        repeat(10_000) { buffer.append(malformed("stress-$it")) }

        val snapshot = buffer.snapshot()
        (snapshot.totalBytes <= capacity) shouldBe true
        snapshot.entries.map { it.sequence }.sorted() shouldBe snapshot.entries.map { it.sequence }
        snapshot.entries.last().sequence shouldBe 10_000L
    }

    private fun malformed(text: String) = LogcatEntry.Malformed(raw = text, reason = "fixture")

    private fun record(message: String, continuations: List<String>) = LogcatEntry.Record(
        record = LogcatRecord(
            timestamp = LogTimestamp(9, 10, 12, 34, 56, 789),
            pid = ProcessId(123),
            tid = ThreadId(456),
            severity = LogSeverity.INFO,
            tag = LogTag("Sample"),
            message = LogMessage(message),
        ),
        continuationLines = continuations,
    )
}
