package dev.acme.adbtoolbox.domain.logcat

// `adb logcat -v threadtime` format: "MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message".
// The tag may itself contain no colon (its own trailing spaces are trimmed below); the message is
// everything after the first colon that follows the level, with at most one separating space
// consumed so a message that starts with a real space is not lossy.
private val THREADTIME_RECORD =
    Regex("""^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+([^:]*):\s?(.*)$""")

// A line that starts with something resembling a date/time prefix but fails the full record
// pattern above (corrupt digit groups, non-numeric pid/tid, a truncated field) — distinguishes a
// "broken header attempt" (Malformed) from ordinary continuation text with no header shape at all.
private val HEADER_LOOKING_PREFIX = Regex("""^\d{2}-\d{2}\s""")

private val DAEMON_MARKER = Regex("""^-{3,}\s*beginning of\s+(\S+)\s*$""")

/**
 * Parses one decoded logcat threadtime line into a [LogcatParseResult]. Stateless and pure — line
 * association across multiple lines (continuations attaching to their record) is
 * [LogcatEntryAssembler]'s job, not this parser's.
 */
object LogcatLineParser {

    fun parse(line: String): LogcatParseResult {
        DAEMON_MARKER.find(line)?.let { match ->
            return LogcatParseResult.DaemonMarker(buffer = match.groupValues[1], raw = line)
        }

        THREADTIME_RECORD.find(line)?.let { match ->
            return parseRecord(match, line)
        }

        if (HEADER_LOOKING_PREFIX.find(line) != null) {
            return LogcatParseResult.Malformed(raw = line, reason = "looks like a threadtime header but does not parse")
        }

        return LogcatParseResult.Continuation(line)
    }

    private fun parseRecord(match: MatchResult, raw: String): LogcatParseResult {
        val groups = match.groupValues

        val month = groups[1].toInt()
        val day = groups[2].toInt()
        val hour = groups[3].toInt()
        val minute = groups[4].toInt()
        val second = groups[5].toInt()
        val millisecond = groups[6].toInt()

        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return LogcatParseResult.Malformed(raw, "timestamp field out of range")
        }

        val pid = groups[7].toIntOrNull() ?: return LogcatParseResult.Malformed(raw, "pid is not a valid integer")
        val tid = groups[8].toIntOrNull() ?: return LogcatParseResult.Malformed(raw, "tid is not a valid integer")
        val severity = LogSeverity.fromLetter(groups[9][0])
            ?: return LogcatParseResult.Malformed(raw, "unrecognized severity letter")

        val record = LogcatRecord(
            timestamp = LogTimestamp(month, day, hour, minute, second, millisecond),
            pid = ProcessId(pid),
            tid = ThreadId(tid),
            severity = severity,
            tag = LogTag(groups[10].trim()),
            message = LogMessage(groups[11]),
        )
        return LogcatParseResult.Parsed(record)
    }
}
