package dev.acme.adbtoolbox.intellij.persistence

/**
 * The settings slice of [AdbToolboxProjectState] (ADR 0006: `adbPath`, `scrcpyPath`, `captureDir`,
 * plus the Logcat buffer size — task 038 — a feature-local state carrier composed into the one
 * root `PersistentStateComponent`). A plain mutable bean (public `var`s, no-arg constructor) as
 * IntelliJ's `XmlSerializer` requires.
 *
 * [schemaVersion] lets [migrateSettingsState] recognize XML written by an older shape this class
 * used to have and upgrade it in place (task 038's migration requirement — a stronger contract
 * than [DeviceSelectionState]/[NavigationPersistenceState]'s "unknown version -> null": a
 * *recognized* older version's data is preserved, not discarded), while a newer, not-yet-understood
 * version still degrades to defaults rather than risk misinterpreting a future shape.
 */
class SettingsPersistenceState {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var adbPathOverride: String? = null
    var scrcpyPathOverride: String? = null
    var captureDirectory: String? = null
    var logcatBufferSizeKb: Int = DEFAULT_LOGCAT_BUFFER_SIZE_KB

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Mirrors [dev.acme.adbtoolbox.domain.settings.SettingsState.DEFAULT_LOGCAT_BUFFER_SIZE_KB]
         * — duplicated here (rather than referenced) so this plain bean stays a self-contained,
         * dependency-free `XmlSerializer` target, matching [DeviceSelectionState]/
         * [NavigationPersistenceState]'s existing style. */
        const val DEFAULT_LOGCAT_BUFFER_SIZE_KB: Int = 16 * 1024
    }
}
