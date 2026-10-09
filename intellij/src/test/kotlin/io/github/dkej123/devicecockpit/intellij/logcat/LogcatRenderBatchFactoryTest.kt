package io.github.dkej123.devicecockpit.intellij.logcat

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.domain.logcat.LogMessage
import io.github.dkej123.devicecockpit.domain.logcat.LogSeverity
import io.github.dkej123.devicecockpit.domain.logcat.LogTag
import io.github.dkej123.devicecockpit.domain.logcat.LogTimestamp
import io.github.dkej123.devicecockpit.domain.logcat.LogcatBufferCursor
import io.github.dkej123.devicecockpit.domain.logcat.LogcatBufferDelta
import io.github.dkej123.devicecockpit.domain.logcat.LogcatBufferSnapshot
import io.github.dkej123.devicecockpit.domain.logcat.LogcatEntry
import io.github.dkej123.devicecockpit.domain.logcat.LogcatRecord
import io.github.dkej123.devicecockpit.domain.logcat.ProcessId
import io.github.dkej123.devicecockpit.domain.logcat.SequencedLogcatEntry
import io.github.dkej123.devicecockpit.domain.logcat.ThreadId

class LogcatRenderBatchFactoryTest : BasePlatformTestCase() {

    fun `test snapshot maps record fields continuations and match spans into a reset batch`() {
        val entry = SequencedLogcatEntry(7, record(), 100)
        val snapshot = LogcatBufferSnapshot(listOf(entry), 100, LogcatBufferCursor(2, 7))
        val spans = listOf(LogcatMatchSpan(0, 4))

        val batch = LogcatRenderBatchFactory.fromSnapshot(snapshot, mapOf(7L to spans))

        assertTrue(batch.reset)
        assertEquals(7L, batch.rows.single().sequence)
        assertEquals(LogSeverity.ERROR, batch.rows.single().severity)
        assertEquals("09-10 12:34:56.789", batch.rows.single().timestamp)
        assertEquals("Sample", batch.rows.single().tag)
        assertEquals("failure\nframe one\nframe two", batch.rows.single().message)
        assertEquals(spans, batch.rows.single().matchSpans)
    }

    fun `test ordinary delta is incremental while a gap becomes a reset`() {
        val entry = SequencedLogcatEntry(8, LogcatEntry.Malformed("raw line", "bad"), 32)
        val cursor = LogcatBufferCursor(0, 8)

        val ordinary = LogcatRenderBatchFactory.fromDelta(LogcatBufferDelta(listOf(entry), cursor, false))
        val gap = LogcatRenderBatchFactory.fromDelta(LogcatBufferDelta(listOf(entry), cursor, true))

        assertFalse(ordinary.reset)
        assertTrue(gap.reset)
        assertEquals("raw line", ordinary.rows.single().message)
        assertNull(ordinary.rows.single().severity)
    }

    fun `test ordinary delta carries the buffer eviction floor into the render batch`() {
        val entry = SequencedLogcatEntry(6, LogcatEntry.Malformed("new", "bad"), 32)
        val delta = LogcatBufferDelta(
            entries = listOf(entry),
            cursor = LogcatBufferCursor(0, 6),
            resetRequired = false,
            oldestRetainedSequence = 3,
        )

        val batch = LogcatRenderBatchFactory.fromDelta(delta)

        assertEquals(3L, batch.retainFromSequence)
    }

    fun `test daemon marker remains a stable renderable row`() {
        val entry = SequencedLogcatEntry(9, LogcatEntry.DaemonMarker("main", "--------- beginning of main"), 40)
        val snapshot = LogcatBufferSnapshot(listOf(entry), 40, LogcatBufferCursor(0, 9))

        val row = LogcatRenderBatchFactory.fromSnapshot(snapshot).rows.single()

        assertEquals(9L, row.sequence)
        assertEquals("--------- beginning of main", row.message)
        assertNull(row.timestamp)
        assertNull(row.tag)
    }

    private fun record() = LogcatEntry.Record(
        LogcatRecord(
            timestamp = LogTimestamp(9, 10, 12, 34, 56, 789),
            pid = ProcessId(123),
            tid = ThreadId(456),
            severity = LogSeverity.ERROR,
            tag = LogTag("Sample"),
            message = LogMessage("failure"),
        ),
        listOf("frame one", "frame two"),
    )
}
