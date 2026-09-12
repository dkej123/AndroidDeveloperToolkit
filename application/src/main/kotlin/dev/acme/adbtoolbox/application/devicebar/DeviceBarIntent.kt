package dev.acme.adbtoolbox.application.devicebar

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/** User-triggered inputs [DeviceBarViewModel] reduces against [DeviceBarViewState] (ADR 0004). */
sealed interface DeviceBarIntent {

    /** Opens the picker popup, highlighting the currently-selected device if one exists. */
    data object OpenPicker : DeviceBarIntent

    /** Closes the picker popup without changing the selection (mouse-away, or Escape). */
    data object ClosePicker : DeviceBarIntent

    /** Moves keyboard highlight to exactly [index] (Up/Down arrow navigation within the popup). */
    data class HighlightAt(val index: Int) : DeviceBarIntent

    /** Applies whichever row is currently highlighted (Enter) — a no-op if nothing is highlighted. */
    data object ConfirmHighlighted : DeviceBarIntent

    /** Direct mouse selection of exactly [serial] — never by row index or displayed name. */
    data class SelectDevice(val serial: DeviceSerial) : DeviceBarIntent

    /** The refresh control's click/shortcut — suppressed while a refresh is already in flight. */
    data object Refresh : DeviceBarIntent

    /**
     * The picker footer's "Pair device over Wi-Fi…" link. Only emits [DeviceBarViewModel.pairOverWifiRequests];
     * the pairing flow itself belongs to task 039 and is deliberately not invented here.
     */
    data object RequestPairOverWifi : DeviceBarIntent
}
