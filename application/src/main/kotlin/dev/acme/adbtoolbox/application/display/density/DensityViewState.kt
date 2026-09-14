package dev.acme.adbtoolbox.application.display.density

import dev.acme.adbtoolbox.domain.display.density.DensityReading

/**
 * Display-density state for the currently eligible device (task 029), platform-neutral (no UI
 * types). Every `reading` field is always a value obtained from an actual `wm density` readback —
 * never the last-requested percent/dpi assumed to have succeeded — mirroring
 * [dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState]'s "readback, never the request"
 * rule for font scale.
 */
sealed interface DensityViewState {

    /** No eligible device yet, or the device just changed and its current reading hasn't been read yet. */
    data object Loading : DensityViewState

    /** [reading] is the device's last-read-back density. */
    data class Idle(val reading: DensityReading) : DensityViewState

    /** A preset/custom apply or a reset is in flight; [reading] is the last known-good readback, if any. */
    data class Applying(val reading: DensityReading?) : DensityViewState

    /** A validation failure or a transport/timeout/cancellation failure; [reading] is the last known-good readback, if any. */
    data class Error(val reading: DensityReading?, val message: String) : DensityViewState
}
