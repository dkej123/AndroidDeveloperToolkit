package dev.acme.adbtoolbox.application.devicebar

/**
 * The device bar's full view state (task 011): [bar] drives the pinned bar row itself, [picker]
 * drives the popup, and [isRefreshing] is the (non-visual) duplicate-refresh-suppression flag a
 * later rendering task may use to disable the refresh control while a refresh is already in
 * flight.
 */
data class DeviceBarViewState(
    val bar: DeviceBarPresentation = DeviceBarPresentation.Loading,
    val picker: DevicePickerState = DevicePickerState(),
    val isRefreshing: Boolean = false,
)
