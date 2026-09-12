package dev.acme.adbtoolbox.application.deviceactions

import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.deviceactions.DeviceActionKind

/**
 * The immutable state of task 016's minimal Device-view basic-action binding: [controlPolicy]
 * mirrors the current device-eligibility signal (task 014) so all three buttons render dimmed with
 * a reason when no device is eligible; [busyAction] is non-null for the whole lifetime of one
 * in-flight action and gates every other action too — task 016's "prevent duplicate in-flight
 * actions" is enforced here, not left to UI button-disabling alone. Replaced wholesale on every
 * reduction, never mutated in place.
 */
data class DeviceActionsViewState(
    val controlPolicy: ControlPolicy = ControlPolicy.Disabled(DeviceCommandContext.Disabled.Loading),
    val busyAction: DeviceActionKind? = null,
)
