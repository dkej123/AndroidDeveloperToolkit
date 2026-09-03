package dev.acme.adbtoolbox.intellij.persistence

/**
 * The device-selection slice of [AdbToolboxProjectState] (ADR 0006: `selectedDeviceSerial`, a
 * feature-local state carrier composed into the one root `PersistentStateComponent`). A plain
 * mutable bean (public `var`s, no-arg constructor) as IntelliJ's `XmlSerializer` requires.
 *
 * [schemaVersion] lets [resolvePersistedSerial] recognize XML written by a shape this class no
 * longer matches (a future field rename/removal) and degrade to "no selection" instead of
 * misinterpreting stale data — bump [CURRENT_SCHEMA_VERSION] whenever this shape changes
 * incompatibly.
 */
class DeviceSelectionState {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var selectedDeviceSerial: String? = null

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
