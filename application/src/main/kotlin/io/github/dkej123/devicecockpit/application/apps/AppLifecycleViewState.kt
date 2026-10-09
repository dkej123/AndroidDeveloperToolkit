package io.github.dkej123.devicecockpit.application.apps

import io.github.dkej123.devicecockpit.domain.devicecontext.ControlPolicy
import io.github.dkej123.devicecockpit.domain.device.DeviceCommandContext

/**
 * The immutable state of task 023's minimal Apps-view Force-stop/Launch/Restart binding.
 * [controlPolicy] mirrors the current device-eligibility signal (task 014), like
 * [io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsViewState]; [selectedPackageName] is
 * `null` whenever no package is selected on the currently active device (per
 * [io.github.dkej123.devicecockpit.domain.apps.SelectedPackageState]'s cross-device-safety contract), in which
 * case every action button renders disabled regardless of [controlPolicy]. [busy] is `true` for the
 * whole lifetime of one in-flight action *for the currently selected package* — a different
 * package's own in-flight action (guarded independently by
 * [io.github.dkej123.devicecockpit.application.apps.AppLifecycleUseCase]) never sets this.
 */
data class AppLifecycleViewState(
    val controlPolicy: ControlPolicy = ControlPolicy.Disabled(DeviceCommandContext.Disabled.Loading),
    val selectedPackageName: String? = null,
    val busy: Boolean = false,
) {
    /** `true` only when a device is eligible, a package is selected, and no action is already running for it. */
    val actionsEnabled: Boolean
        get() = controlPolicy is ControlPolicy.Enabled && selectedPackageName != null && !busy
}
