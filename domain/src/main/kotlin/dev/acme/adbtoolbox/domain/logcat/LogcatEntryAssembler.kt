package dev.acme.adbtoolbox.domain.logcat

/**
 * Folds a stream of decoded logcat lines into [LogcatEntry] values, attaching each
 * [LogcatParseResult.Continuation] line to the [LogcatEntry.Record] it follows instead of emitting
 * it standalone. A continuation with no preceding record (e.g. the very first line of a stream, or
 * one immediately after a daemon marker/malformed line) is never silently dropped — it becomes its
 * own [LogcatEntry.Malformed]. Pure data transformation, no coroutines/IO; call [finish] once the
 * underlying line stream ends to flush any pending record. Not thread-safe: one instance per
 * stream.
 */
class LogcatEntryAssembler {

    private var pendingRecord: LogcatRecord? = null
    private val pendingContinuations = mutableListOf<String>()

    fun accept(line: String): List<LogcatEntry> = accept(LogcatLineParser.parse(line))

    fun accept(result: LogcatParseResult): List<LogcatEntry> = when (result) {
        is LogcatParseResult.Parsed -> {
            val flushed = flushPending()
            pendingRecord = result.record
            flushed
        }

        is LogcatParseResult.Continuation -> {
            val record = pendingRecord
            if (record != null) {
                pendingContinuations += result.text
                emptyList()
            } else {
                listOf(LogcatEntry.Malformed(result.text, "continuation line with no preceding record"))
            }
        }

        is LogcatParseResult.DaemonMarker ->
            flushPending() + LogcatEntry.DaemonMarker(result.buffer, result.raw)

        is LogcatParseResult.Malformed ->
            flushPending() + LogcatEntry.Malformed(result.raw, result.reason)
    }

    /** Flushes any pending record. Call once the underlying line stream has ended. */
    fun finish(): List<LogcatEntry> = flushPending()

    private fun flushPending(): List<LogcatEntry> {
        val record = pendingRecord ?: return emptyList()
        pendingRecord = null
        val entry = LogcatEntry.Record(record, pendingContinuations.toList())
        pendingContinuations.clear()
        return listOf(entry)
    }
}
