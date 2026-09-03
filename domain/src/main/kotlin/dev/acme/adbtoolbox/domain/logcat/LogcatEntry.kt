package dev.acme.adbtoolbox.domain.logcat

/**
 * One assembled logcat entry, as produced by [LogcatEntryAssembler] folding a stream of
 * [LogcatParseResult]s together. A multi-line record (e.g. a stack trace) becomes one [Record]
 * with its continuation lines attached, rather than several disconnected lines.
 */
sealed interface LogcatEntry {
    data class Record(val record: LogcatRecord, val continuationLines: List<String>) : LogcatEntry

    data class DaemonMarker(val buffer: String, val raw: String) : LogcatEntry

    data class Malformed(val raw: String, val reason: String) : LogcatEntry
}
