package io.github.dkej123.devicecockpit.application.device

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial

/** User/system-triggered inputs [SelectedDeviceViewModel] reduces against [io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState] (ADR 0004). */
sealed interface SelectedDeviceIntent {
    /** Explicit user selection of exactly [serial] — never inferred/implicit (task 009). */
    data class Select(val serial: DeviceSerial) : SelectedDeviceIntent

    /** Explicit user deselection ("no device"), persisted the same as any other selection. */
    data object ClearSelection : SelectedDeviceIntent

    /** Retries resolving the persisted selection after a [io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState.Error]. */
    data object RetryRestore : SelectedDeviceIntent
}
