package dev.acme.adbtoolbox.domain.device

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * The one explicit selected-device context for a project (task 009), derived from
 * [DeviceRepository.devices] and a persisted/user-chosen [DeviceSerial] — never from implicitly
 * picking the first/only device (adb-development: "implicit single/first-device selection ...
 * must not be inherited"). A missing or no-longer-eligible selection always surfaces as [None] or
 * [Disconnected], never a silent substitution for a different device.
 */
sealed interface SelectedDeviceState {

    /** The device repository/persisted selection has not resolved yet — no decision can be made. */
    data object Loading : SelectedDeviceState

    /** No device is selected: no persisted serial, or the persisted serial was never found. */
    data object None : SelectedDeviceState

    /** The selected device is present and [DeviceConnectionState.Online] — usable for commands. */
    data class Online(val device: Device) : SelectedDeviceState

    /** The selected device is present but [DeviceConnectionState.Unauthorized]. */
    data class Unauthorized(val device: Device) : SelectedDeviceState

    /** The selected device is present but [DeviceConnectionState.Offline]. */
    data class Offline(val device: Device) : SelectedDeviceState

    /**
     * The selected device is present but in some other non-eligible [DeviceConnectionState]
     * ([DeviceConnectionState.NoPermissions], [DeviceConnectionState.Authorizing], or
     * [DeviceConnectionState.Unknown]) — kept distinct from [Offline]/[Unauthorized] rather than
     * coerced into one of them, since [Device.state] is never fabricated (task 008).
     */
    data class Ineligible(val device: Device) : SelectedDeviceState

    /**
     * A device was selected (explicitly, or restored from persistence) but its exact [serial] no
     * longer appears in the live device list — e.g. unplugged. Reappearance of the same serial
     * (task 008's repository re-emits) resolves this back to [Online]/[Unauthorized]/etc.
     * automatically; it is never silently swapped for a different device.
     */
    data class Disconnected(val serial: DeviceSerial) : SelectedDeviceState

    /** A recoverable failure resolving selection (e.g. an unexpected persistence read failure). */
    data class Error(val message: String) : SelectedDeviceState
}
