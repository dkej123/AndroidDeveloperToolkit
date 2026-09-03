package dev.acme.adbtoolbox.domain.devicecontext

import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext

/**
 * The platform-neutral enabled/disabled+reason signal every device-mutating control renders from
 * (task 014's scope: "keeping all controls visible at the presentation-contract level" —
 * `design/README.md`'s "Never hide controls — dim them" rule). This is deliberately not a new
 * enabled/disabled vocabulary: it reuses task 009's [DeviceCommandContext] (already covering every
 * [SelectedDeviceState] with the exact reason a call site needs) rather than re-deriving one, so a
 * feature never has two different disabled-reason shapes to reconcile.
 */
sealed interface ControlPolicy {

    /** The control is fully usable — a device is selected and eligible. */
    data object Enabled : ControlPolicy

    /** The control renders dimmed with [reason] available for its tooltip/status text. */
    data class Disabled(val reason: DeviceCommandContext.Disabled) : ControlPolicy
}

/** Derives the [ControlPolicy] a device-mutating control renders from the current [DeviceCommandContext]. */
fun DeviceCommandContext.controlPolicy(): ControlPolicy = when (this) {
    is DeviceCommandContext.Eligible -> ControlPolicy.Enabled
    is DeviceCommandContext.Disabled -> ControlPolicy.Disabled(this)
}

/** Derives the [ControlPolicy] a device-mutating control renders from the current [SelectedDeviceState]. */
fun SelectedDeviceState.controlPolicy(): ControlPolicy = toCommandContext().controlPolicy()
