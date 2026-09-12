package dev.acme.adbtoolbox.domain.apps

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * One package explicitly selected in the Apps view (task 022), scoped to the exact [serial] it was
 * selected on. Carrying [serial] alongside [packageName] — rather than a bare package-name string —
 * is what lets every consumer (the Apps reducer itself, and Logcat's future default-package-filter
 * per `design/README.md`'s "Selection sharing" note) tell a same-device selection apart from a
 * different device's leftover selection, so a selection never silently applies to the wrong device.
 */
data class SelectedPackage(
    val serial: DeviceSerial,
    val packageName: String,
)
