package dev.acme.adbtoolbox.domain.device

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * The command guard (task 009): every ADB command call site derives its target from this instead
 * of an implicit "current device" global. [Eligible] is the only variant carrying a [DeviceSerial],
 * so a caller cannot construct a command context with a serial unless a device is genuinely
 * selected and online — the guarantee is structural (the type), not merely tested.
 */
sealed interface DeviceCommandContext {

    /** A currently eligible device is selected; [serial] is safe to target with an ADB command. */
    data class Eligible(val serial: DeviceSerial) : DeviceCommandContext

    /** No command can be issued right now; [reason] is why, for UI/feature code to render. */
    sealed interface Disabled : DeviceCommandContext {

        /** Selection has not resolved yet (repository/persistence still loading). */
        data object Loading : Disabled

        /** No device is selected. */
        data object NoDeviceSelected : Disabled

        /** A device is selected but not currently eligible (not online). */
        data class DeviceNotEligible(val reason: DeviceConnectionState) : Disabled

        /** The selected device's serial is no longer present in the live device list. */
        data class DeviceStale(val serial: DeviceSerial) : Disabled

        /** Selection state could not be resolved due to a recoverable error. */
        data class SelectionError(val message: String) : Disabled
    }
}

/** Derives the [DeviceCommandContext] a call site must use from the current [SelectedDeviceState]. */
fun SelectedDeviceState.toCommandContext(): DeviceCommandContext = when (this) {
    is SelectedDeviceState.Loading -> DeviceCommandContext.Disabled.Loading
    is SelectedDeviceState.None -> DeviceCommandContext.Disabled.NoDeviceSelected
    is SelectedDeviceState.Online -> DeviceCommandContext.Eligible(device.serial)
    is SelectedDeviceState.Unauthorized -> DeviceCommandContext.Disabled.DeviceNotEligible(device.state)
    is SelectedDeviceState.Offline -> DeviceCommandContext.Disabled.DeviceNotEligible(device.state)
    is SelectedDeviceState.Ineligible -> DeviceCommandContext.Disabled.DeviceNotEligible(device.state)
    is SelectedDeviceState.Disconnected -> DeviceCommandContext.Disabled.DeviceStale(serial)
    is SelectedDeviceState.Error -> DeviceCommandContext.Disabled.SelectionError(message)
}

/**
 * The [DeviceSerial] this [SelectedDeviceState] is currently associated with, if any — unlike
 * [toCommandContext]'s [DeviceCommandContext.Eligible], this is not restricted to online devices:
 * a device that just went offline/unauthorized/disconnected keeps its serial here, since per-serial
 * data (task 014's override summaries, "remember applied overrides per serial and offer to re-apply
 * when that serial returns" — `design/README.md`) is tracked independently of eligibility.
 */
val SelectedDeviceState.selectedSerialOrNull: DeviceSerial?
    get() = when (this) {
        is SelectedDeviceState.Online -> device.serial
        is SelectedDeviceState.Unauthorized -> device.serial
        is SelectedDeviceState.Offline -> device.serial
        is SelectedDeviceState.Ineligible -> device.serial
        is SelectedDeviceState.Disconnected -> serial
        SelectedDeviceState.Loading, SelectedDeviceState.None -> null
        is SelectedDeviceState.Error -> null
    }
