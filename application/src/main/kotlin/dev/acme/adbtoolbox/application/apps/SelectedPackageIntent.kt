package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/** User/system-triggered inputs [SelectedPackageViewModel] reduces against its state (ADR 0004). */
sealed interface SelectedPackageIntent {

    /** Selects exactly [packageName] on exactly [serial] — never inferred from a list index/name. */
    data class Select(val serial: DeviceSerial, val packageName: String) : SelectedPackageIntent

    /** Clears the current selection, regardless of which device it belonged to. */
    data object Clear : SelectedPackageIntent
}
