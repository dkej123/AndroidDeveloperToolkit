package dev.acme.adbtoolbox.application.capture

import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy

/**
 * The immutable state of the minimal task-019 capture binding: [controlPolicy] mirrors the current
 * device-eligibility signal (task 014) so the capture control renders dimmed with a reason when no
 * device is eligible; [isCapturing] disables re-entrant capture attempts while one is in flight;
 * [lastCapture] is set only once a capture has genuinely committed to disk — the Reveal action is
 * offered if and only if this is non-null, so it can never point at a partial/failed/nonexistent
 * file. Replaced wholesale on every reduction, never mutated in place.
 */
data class CaptureViewState(
    val controlPolicy: ControlPolicy = ControlPolicy.Disabled(DeviceCommandContext.Disabled.Loading),
    val isCapturing: Boolean = false,
    val lastCapture: CaptureLocation? = null,
)
