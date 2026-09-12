package dev.acme.adbtoolbox.domain.recording

import kotlin.time.Duration

/**
 * Renders an elapsed recording span as `design/README.md` §3/§8's mono `mm:ss` label (e.g.
 * "Recording · 00:42" / status-bar "REC 00:42"). A negative [duration] — never expected in
 * practice, but possible if a future [dev.acme.adbtoolbox.domain.time.MonotonicClock] reading ever
 * raced its own [dev.acme.adbtoolbox.domain.recording.RecordingSessionState.Recording.startedAt]
 * mark — clamps to zero rather than rendering a misleading minus sign. Kept KMP-ready: no
 * `String.format`/JVM-only formatting.
 */
fun formatElapsed(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0)
    val minutes = (totalSeconds / 60).toString().padStart(2, '0')
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return "$minutes:$seconds"
}
