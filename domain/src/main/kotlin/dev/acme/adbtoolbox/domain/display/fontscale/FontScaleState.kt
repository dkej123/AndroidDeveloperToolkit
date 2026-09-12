package dev.acme.adbtoolbox.domain.display.fontscale

/**
 * Font-scale state for the currently eligible device (task 026), platform-neutral (no UI types).
 * [Idle.current] and every other `current` field is always a value obtained by reading the device
 * back — never the last-requested value assumed to have succeeded, per the task's acceptance
 * criterion ("Override state reflects readback, never the requested value alone").
 */
sealed interface FontScaleState {

    /** No eligible device yet, or the device just changed and its current value hasn't been read yet. */
    data object Loading : FontScaleState

    /** [current] is the device's last-read-back font-scale value. */
    data class Idle(val current: Double) : FontScaleState

    /** A write + readback is in flight; [current] is the last known-good readback, if any; [target] is the requested value. */
    data class Applying(val current: Double?, val target: Double) : FontScaleState

    /** A validation failure or a transport/timeout/cancellation failure; [current] is the last known-good readback, if any. */
    data class Error(val current: Double?, val message: String) : FontScaleState
}
