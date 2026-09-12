package dev.acme.adbtoolbox.application.devicebar

import dev.acme.adbtoolbox.domain.device.Device

/**
 * The device bar's own presentation model (task 011), reduced from
 * [dev.acme.adbtoolbox.domain.device.SelectedDeviceState] — the six states the task calls out
 * explicitly (loading, none, online, unauthorized, offline, error). [dev.acme.adbtoolbox.domain.device.SelectedDeviceState.Ineligible]
 * and [dev.acme.adbtoolbox.domain.device.SelectedDeviceState.Disconnected] both fold into [Error]:
 * both are anomalous, non-command-eligible conditions the bar surfaces generically rather than by
 * further multiplying presentation states the design has not specified copy/treatment for
 * (`design/README.md` §1 lists exactly loading/connected/unauthorized/no-device).
 *
 * No color, icon, spacing, or copy is decided here — only the data a later rendering task (042)
 * needs. [Online.onlineCount] is business data (the "N online" count on the bar), not a visual
 * value, so it is computed here rather than deferred.
 */
sealed interface DeviceBarPresentation {
    data object Loading : DeviceBarPresentation
    data object NoDevice : DeviceBarPresentation
    data class Online(val device: Device, val onlineCount: Int) : DeviceBarPresentation
    data class Unauthorized(val device: Device) : DeviceBarPresentation
    data class Offline(val device: Device) : DeviceBarPresentation
    data class Error(val message: String) : DeviceBarPresentation
}
