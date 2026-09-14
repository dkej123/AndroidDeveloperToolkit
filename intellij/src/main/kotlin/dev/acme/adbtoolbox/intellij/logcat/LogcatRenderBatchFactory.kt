package dev.acme.adbtoolbox.intellij.logcat

import dev.acme.adbtoolbox.domain.logcat.LogcatBufferDelta
import dev.acme.adbtoolbox.domain.logcat.LogcatBufferSnapshot
import dev.acme.adbtoolbox.domain.logcat.LogcatEntry
import dev.acme.adbtoolbox.domain.logcat.LogTimestamp
import dev.acme.adbtoolbox.domain.logcat.SequencedLogcatEntry

/** Maps task 034's immutable buffer views to task 036 renderer batches. */
object LogcatRenderBatchFactory {
    fun fromSnapshot(
        snapshot: LogcatBufferSnapshot,
        matchSpans: Map<Long, List<LogcatMatchSpan>> = emptyMap(),
    ): LogcatRenderBatch = LogcatRenderBatch(
        rows = snapshot.entries.map { it.toRenderRow(matchSpans[it.sequence].orEmpty()) },
        reset = true,
    )

    fun fromDelta(
        delta: LogcatBufferDelta,
        matchSpans: Map<Long, List<LogcatMatchSpan>> = emptyMap(),
    ): LogcatRenderBatch = LogcatRenderBatch(
        rows = delta.entries.map { it.toRenderRow(matchSpans[it.sequence].orEmpty()) },
        reset = delta.resetRequired,
        retainFromSequence = delta.oldestRetainedSequence,
    )

    /** The exact rendered message text a row for [entry] carries — exposed (task 037) so a
     * presentation controller can locate search-match spans against the same text this factory
     * renders, rather than duplicating this join logic. */
    fun messageTextFor(entry: LogcatEntry): String = when (entry) {
        is LogcatEntry.Record -> buildList {
            add(entry.record.message.value)
            addAll(entry.continuationLines)
        }.joinToString("\n")

        is LogcatEntry.DaemonMarker -> entry.raw
        is LogcatEntry.Malformed -> entry.raw
    }

    private fun SequencedLogcatEntry.toRenderRow(spans: List<LogcatMatchSpan>): LogcatRenderRow =
        when (val source = entry) {
            is LogcatEntry.Record -> LogcatRenderRow(
                sequence = sequence,
                severity = source.record.severity,
                timestamp = source.record.timestamp.render(),
                tag = source.record.tag.value,
                message = messageTextFor(source),
                matchSpans = spans.toList(),
            )

            is LogcatEntry.DaemonMarker -> LogcatRenderRow(
                sequence = sequence,
                severity = null,
                timestamp = null,
                tag = null,
                message = messageTextFor(source),
                matchSpans = spans.toList(),
            )

            is LogcatEntry.Malformed -> LogcatRenderRow(
                sequence = sequence,
                severity = null,
                timestamp = null,
                tag = null,
                message = messageTextFor(source),
                matchSpans = spans.toList(),
            )
        }

    private fun LogTimestamp.render(): String =
        "${month.padded(2)}-${day.padded(2)} ${hour.padded(2)}:${minute.padded(2)}:" +
            "${second.padded(2)}.${millisecond.padded(3)}"

    private fun Int.padded(length: Int): String = toString().padStart(length, '0')
}
