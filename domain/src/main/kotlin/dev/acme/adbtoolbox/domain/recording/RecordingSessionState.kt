package dev.acme.adbtoolbox.domain.recording

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import kotlin.time.Duration

/**
 * The lifecycle of one remote `screenrecord` session, owned per explicit [serial] (task 020,
 * mirroring task 017's [dev.acme.adbtoolbox.domain.mirroring.MirroringSessionState] per-serial
 * ownership shape): a session for device A is tracked, started, and stopped independently of any
 * session for device B, and switching the globally selected device elsewhere never disturbs either.
 * Every variant carries [serial] so the originating device is never lost mid-transition.
 */
sealed interface RecordingSessionState {
    val serial: DeviceSerial

    /** No session has been started for [serial] yet, or a prior session's terminal outcome
     * ([Saved]/[Error]) was acknowledged and cleared back to idle. */
    data class Idle(override val serial: DeviceSerial) : RecordingSessionState

    /** The remote `screenrecord` command is being launched; no recording is confirmed running yet. */
    data class Starting(override val serial: DeviceSerial) : RecordingSessionState

    /** `screenrecord` is running remotely, started at [startedAt] — a
     * [dev.acme.adbtoolbox.domain.time.MonotonicClock] mark, never a wall-clock timestamp — so
     * elapsed time is always a subtraction against a later mark, immune to clock adjustments. */
    data class Recording(override val serial: DeviceSerial, val startedAt: Duration) : RecordingSessionState

    /** A stop — either an explicit request or ADB's own maximum-duration auto-completion — is
     * tearing the remote process down; the recording has not yet been retrieved. */
    data class Stopping(override val serial: DeviceSerial) : RecordingSessionState

    /** The remote process has ended; the recording is being pulled from the device into a local file. */
    data class Pulling(override val serial: DeviceSerial) : RecordingSessionState

    /** The recording was pulled and committed to a local file at [location] — the only state that
     * may gate a Reveal action (task 020's acceptance criteria: "Reveal appears only for a confirmed
     * local MP4"), since [location] only ever exists once [dev.acme.adbtoolbox.domain.capture.CaptureTarget.commit]
     * has genuinely succeeded. */
    data class Saved(override val serial: DeviceSerial, val location: CaptureLocation) : RecordingSessionState

    /** The session failed for [error] — see [RecordingSessionError] for the explicit partial-failure
     * case where the remote recording stopped successfully but retrieving it did not. */
    data class Error(override val serial: DeviceSerial, val error: RecordingSessionError) : RecordingSessionState
}
