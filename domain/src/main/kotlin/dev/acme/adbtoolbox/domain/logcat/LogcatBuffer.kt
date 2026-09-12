package dev.acme.adbtoolbox.domain.logcat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val DEFAULT_LOGCAT_BUFFER_BYTES: Int = 16 * 1024 * 1024
const val DEFAULT_LOGCAT_ENTRY_CEILING_BYTES: Int = 256 * 1024

data class LogcatBufferCursor(val generation: Long, val sequence: Long)

data class SequencedLogcatEntry(
    val sequence: Long,
    val entry: LogcatEntry,
    val sizeBytes: Int,
)

data class LogcatBufferSnapshot(
    val entries: List<SequencedLogcatEntry>,
    val totalBytes: Int,
    val cursor: LogcatBufferCursor,
)

data class LogcatBufferDelta(
    val entries: List<SequencedLogcatEntry>,
    val cursor: LogcatBufferCursor,
    val resetRequired: Boolean,
    val oldestRetainedSequence: Long? = null,
)

sealed interface LogcatAppendResult {
    data class Added(
        val entry: SequencedLogcatEntry,
        val cursor: LogcatBufferCursor,
        val entryCount: Int,
        val totalBytes: Int,
    ) : LogcatAppendResult
    data class RejectedOversize(val sizeBytes: Int, val ceilingBytes: Int) : LogcatAppendResult
}

/** Task 034's KMP-ready, concurrency-safe bounded Logcat history. */
class LogcatBuffer(
    val capacityBytes: Int = DEFAULT_LOGCAT_BUFFER_BYTES,
    val entryCeilingBytes: Int = minOf(DEFAULT_LOGCAT_ENTRY_CEILING_BYTES, capacityBytes),
) {
    private val mutex = Mutex()
    private val entries = ArrayDeque<SequencedLogcatEntry>()
    private var totalBytes = 0
    private var lastSequence = 0L
    private var generation = 0L

    init {
        require(capacityBytes > 0)
        require(entryCeilingBytes in 1..capacityBytes)
    }

    suspend fun append(entry: LogcatEntry): LogcatAppendResult = mutex.withLock {
        val frozenEntry = entry.freeze()
        val sizeBytes = frozenEntry.estimatedSizeBytes()
        if (sizeBytes > entryCeilingBytes) {
            return@withLock LogcatAppendResult.RejectedOversize(sizeBytes, entryCeilingBytes)
        }

        val sequenced = SequencedLogcatEntry(++lastSequence, frozenEntry, sizeBytes)
        entries.addLast(sequenced)
        totalBytes += sizeBytes
        while (totalBytes > capacityBytes) {
            totalBytes -= entries.removeFirst().sizeBytes
        }
        LogcatAppendResult.Added(sequenced, cursorLocked(), entries.size, totalBytes)
    }

    suspend fun snapshot(): LogcatBufferSnapshot = mutex.withLock { snapshotLocked() }

    suspend fun deltaAfter(cursor: LogcatBufferCursor?): LogcatBufferDelta = mutex.withLock {
        val currentCursor = cursorLocked()
        val oldestSequence = entries.firstOrNull()?.sequence
        val resetRequired = cursor != null && (
            cursor.generation != generation ||
                cursor.sequence > lastSequence ||
                (oldestSequence != null && cursor.sequence < oldestSequence - 1)
            )
        val deltaEntries = if (cursor == null || resetRequired) {
            entries.toList()
        } else {
            entries.filter { it.sequence > cursor.sequence }
        }
        LogcatBufferDelta(deltaEntries, currentCursor, resetRequired, oldestSequence)
    }

    suspend fun clear(): LogcatBufferSnapshot = mutex.withLock {
        entries.clear()
        totalBytes = 0
        generation++
        snapshotLocked()
    }

    private fun snapshotLocked(): LogcatBufferSnapshot =
        LogcatBufferSnapshot(entries.toList(), totalBytes, cursorLocked())

    private fun cursorLocked(): LogcatBufferCursor = LogcatBufferCursor(generation, lastSequence)
}

private fun LogcatEntry.freeze(): LogcatEntry = when (this) {
    is LogcatEntry.Record -> copy(continuationLines = continuationLines.toList())
    is LogcatEntry.DaemonMarker, is LogcatEntry.Malformed -> this
}

fun LogcatEntry.estimatedSizeBytes(): Int = when (this) {
    is LogcatEntry.Record ->
        record.tag.value.encodeToByteArray().size +
            record.message.value.encodeToByteArray().size +
            continuationLines.sumOf { it.encodeToByteArray().size } +
            32

    is LogcatEntry.DaemonMarker -> buffer.encodeToByteArray().size + raw.encodeToByteArray().size + 16
    is LogcatEntry.Malformed -> raw.encodeToByteArray().size + reason.encodeToByteArray().size + 16
}
