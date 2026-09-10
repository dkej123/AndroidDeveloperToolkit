package dev.acme.adbtoolbox.domain.adb

/**
 * One event from [AdbTransport.executeStream] (e.g. `logcat -v threadtime`). A stream emits zero
 * or more stdout [Line] and diagnostic [StderrLine] events followed by exactly one terminal
 * [Completed] event. Transports such as ddmlib whose protocol merges both channels honestly expose
 * their combined output as [Line]. Cancelling collection tears down the underlying
 * process/receiver (ADR 0005) — it never just stops reading and leaves the source running.
 */
sealed interface AdbStreamEvent {
    data class Line(val text: String) : AdbStreamEvent

    /** A diagnostic line from transports that can distinguish stderr (binary adb). */
    data class StderrLine(val text: String) : AdbStreamEvent

    data class Completed(val outcome: AdbOutcome) : AdbStreamEvent
}
