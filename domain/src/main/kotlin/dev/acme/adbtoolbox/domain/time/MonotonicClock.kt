package dev.acme.adbtoolbox.domain.time

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A monotonic elapsed-time source (task 020, KMP-ready): [markNow] returns a [Duration] offset from
 * an arbitrary, implementation-defined origin — never wall-clock time — so two readings can be
 * subtracted to get a genuine elapsed span immune to clock adjustments, DST, or NTP corrections.
 * Kept as its own injectable port (mirroring how [kotlinx.datetime.Clock] is already injected
 * elsewhere in this codebase) so screen-recording's elapsed-time computation
 * ([dev.acme.adbtoolbox.domain.recording.RecordingSessionState.Recording]) is testable with a
 * [FakeMonotonicClock] rather than any real delay.
 */
fun interface MonotonicClock {
    fun markNow(): Duration
}

/** The production [MonotonicClock]: [kotlin.time.TimeSource.Monotonic], common to every Kotlin target. */
object SystemMonotonicClock : MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun markNow(): Duration = origin.elapsedNow()
}
