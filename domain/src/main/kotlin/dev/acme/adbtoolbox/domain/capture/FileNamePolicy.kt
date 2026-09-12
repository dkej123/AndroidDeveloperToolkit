package dev.acme.adbtoolbox.domain.capture

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Derives the base screenshot file name (before any collision-avoidance suffix a
 * [CaptureDestination] may append) for a capture taken at [timestamp]. Kept KMP-ready by taking a
 * [kotlinx.datetime.Instant] rather than a JVM-only clock/formatting type.
 */
fun interface FileNamePolicy {
    fun baseFileName(timestamp: Instant): String
}

/**
 * The production [FileNamePolicy]: `design/IMPLEMENTATION.md` §4's `screen-<ts>.png` naming scheme.
 * [extension] defaults to task 019's PNG screenshots; task 020 reuses this same policy for its MP4
 * recordings by passing `extension = "mp4"` rather than duplicating the timestamp-formatting logic.
 */
class TimestampFileNamePolicy(
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val extension: String = "png",
) : FileNamePolicy {

    override fun baseFileName(timestamp: Instant): String {
        val local = timestamp.toLocalDateTime(zone)
        return "screen-${local.year}${local.monthNumber.pad2()}${local.dayOfMonth.pad2()}-" +
            "${local.hour.pad2()}${local.minute.pad2()}${local.second.pad2()}.$extension"
    }

    private fun Int.pad2(): String = toString().padStart(2, '0')
}
