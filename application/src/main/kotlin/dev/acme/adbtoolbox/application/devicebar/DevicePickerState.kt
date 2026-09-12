package dev.acme.adbtoolbox.application.devicebar

/**
 * The device picker popup's own state (task 011): whether it is open, its rows (always in
 * [dev.acme.adbtoolbox.domain.device.DeviceRepository]'s current order), and which row is
 * keyboard-highlighted. [highlightedIndex] is `-1` when nothing is highlighted (popup closed, or
 * the device list is empty) — it is never coerced to `0` just because the list is non-empty, so a
 * fresh popup open only highlights the currently-selected device (or the first row as a sane
 * keyboard-navigation starting point), never an arbitrary one.
 */
data class DevicePickerState(
    val isOpen: Boolean = false,
    val items: List<DevicePickerItem> = emptyList(),
    val highlightedIndex: Int = -1,
)
