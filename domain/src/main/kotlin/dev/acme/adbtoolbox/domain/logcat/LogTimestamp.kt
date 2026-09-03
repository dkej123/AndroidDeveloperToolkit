package dev.acme.adbtoolbox.domain.logcat

/**
 * A logcat threadtime timestamp (`MM-DD HH:MM:SS.mmm`). The wire format carries no year, so this
 * is not a point in time on its own — [LogcatLineParser] only constructs one after validating
 * every field is in range; an out-of-range timestamp is a [LogcatParseResult.Malformed] line, not
 * a thrown exception.
 */
data class LogTimestamp(
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millisecond: Int,
)
