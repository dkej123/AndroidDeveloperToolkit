package dev.acme.adbtoolbox.domain.adb

/**
 * How an [AdbTransport] call ended. Distinguishes every case a caller must handle explicitly
 * (ADR 0005) rather than collapsing them into a boolean or a thrown exception:
 * - [Completed] — the call reached the device; [exitCode] is `null` when the transport has no OS
 *   exit code to report (e.g. a ddmlib call with no process behind it), never coerced to `0`/`-1`.
 * - [TimedOut] — the caller-supplied timeout elapsed before completion.
 * - [Cancelled] — the calling coroutine was cancelled; distinct from [TimedOut] so callers can
 *   treat "the user navigated away" differently from "the device didn't answer in time."
 * - [TransportFailure] — the call could not reach the device at all (offline, unauthorized,
 *   disconnected, bridge initialization failure, connection reset).
 * - [Unsupported] — the selected transport has no equivalent for this operation (e.g. ddmlib has
 *   no wireless pairing) — a transport limitation, not a device- or call-level failure.
 */
sealed interface AdbOutcome {
    data class Completed(val exitCode: Int?) : AdbOutcome

    data object TimedOut : AdbOutcome

    data object Cancelled : AdbOutcome

    data class TransportFailure(val reason: String) : AdbOutcome

    data class Unsupported(val reason: String) : AdbOutcome
}
