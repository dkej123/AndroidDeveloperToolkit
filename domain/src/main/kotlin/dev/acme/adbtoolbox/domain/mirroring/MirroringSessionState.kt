package dev.acme.adbtoolbox.domain.mirroring

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.discovery.ToolVersion

/**
 * The lifecycle of one scrcpy mirroring session, owned per explicit [serial] (never an implicit
 * "current device") — a caller tracks a session for device A independently of any session for
 * device B, and switching the globally selected device elsewhere never disturbs either. Every
 * variant carries [serial] so the originating device is never lost, even mid-transition.
 */
sealed interface MirroringSessionState {
    val serial: DeviceSerial

    /** No session has been started for [serial] yet, or a prior session's outcome was acknowledged
     * and cleared. */
    data class Idle(override val serial: DeviceSerial) : MirroringSessionState

    /** `scrcpy` is being resolved and launched; no process is confirmed running yet. */
    data class Starting(override val serial: DeviceSerial) : MirroringSessionState

    /** The scrcpy process is running, resolved at [scrcpyVersion] (task 004's version-query
     * detection) so later option handling (task 040) can reject/adapt incompatible combinations. */
    data class Running(override val serial: DeviceSerial, val scrcpyVersion: ToolVersion) : MirroringSessionState

    /** A `stop()` call is tearing the process down; not yet confirmed exited. */
    data class Stopping(override val serial: DeviceSerial) : MirroringSessionState

    /** The session ran (at least [Starting]) and has since ended for [reason]. */
    data class Exited(override val serial: DeviceSerial, val reason: MirroringExitReason) : MirroringSessionState

    /** The session could not be started, or ended abnormally before ever confirming a version, for
     * [error]. */
    data class Error(override val serial: DeviceSerial, val error: MirroringSessionError) : MirroringSessionState
}
