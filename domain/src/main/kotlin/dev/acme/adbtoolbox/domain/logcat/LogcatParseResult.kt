package dev.acme.adbtoolbox.domain.logcat

/**
 * The result of parsing one decoded logcat line, per [LogcatLineParser]. Richer than
 * `AdbParseResult`'s Parsed/Malformed convention (`domain.adb.AdbParseResult`) because a single
 * threadtime line can be one of four distinct things, not just "parsed or not":
 * - [Parsed] — a complete record header line.
 * - [DaemonMarker] — logcat's own `--------- beginning of <buffer>` section marker, not a device
 *   record at all.
 * - [Continuation] — a line with no header, e.g. a stack trace frame belonging to the previous
 *   record; [LogcatEntryAssembler] attaches it to that record rather than treating it as
 *   standalone.
 * - [Malformed] — a line that looks like it was attempting to be a record/marker but is corrupt
 *   (out-of-range fields, non-numeric pid/tid, a truncated header prefix), preserved verbatim
 *   rather than dropped.
 */
sealed interface LogcatParseResult {
    data class Parsed(val record: LogcatRecord) : LogcatParseResult

    data class DaemonMarker(val buffer: String, val raw: String) : LogcatParseResult

    data class Continuation(val text: String) : LogcatParseResult

    data class Malformed(val raw: String, val reason: String) : LogcatParseResult
}
