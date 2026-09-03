package dev.acme.adbtoolbox.application.device

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/** User/system-triggered inputs [SelectedDeviceViewModel] reduces against [dev.acme.adbtoolbox.domain.device.SelectedDeviceState] (ADR 0004). */
sealed interface SelectedDeviceIntent {
    /** Explicit user selection of exactly [serial] — never inferred/implicit (task 009). */
    data class Select(val serial: DeviceSerial) : SelectedDeviceIntent

    /** Explicit user deselection ("no device"), persisted the same as any other selection. */
    data object ClearSelection : SelectedDeviceIntent

    /** Retries resolving the persisted selection after a [dev.acme.adbtoolbox.domain.device.SelectedDeviceState.Error]. */
    data object RetryRestore : SelectedDeviceIntent
}
