package dev.acme.adbtoolbox.domain.adb

/**
 * One event from [AdbTransport.executeStream] (e.g. `logcat -v threadtime`). A stream emits zero
 * or more [Line] events followed by exactly one terminal [Completed] event. Cancelling the
 * collecting coroutine tears down the underlying process/receiver (ADR 0005) — it never just stops
 * reading and leaves the source running.
 */
sealed interface AdbStreamEvent {
    data class Line(val text: String) : AdbStreamEvent

    data class Completed(val outcome: AdbOutcome) : AdbStreamEvent
}
