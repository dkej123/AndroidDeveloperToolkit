package dev.acme.adbtoolbox.intellij.persistence

/**
 * The mirroring-options slice of [AdbToolboxProjectState] (task 040), matching
 * [SettingsPersistenceState]'s versioned-bean shape: a plain mutable bean (public `var`s, no-arg
 * constructor) as IntelliJ's `XmlSerializer` requires, with [schemaVersion] letting
 * [migrateMirroringOptionsState] recognize and upgrade an older persisted shape rather than
 * discarding it.
 */
class MirroringOptionsPersistenceState {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var stayAwake: Boolean = false
    var showTouches: Boolean = false
    var maxSize: Int = NO_LIMIT
    var videoBitRateMbps: Int = NO_LIMIT

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Sentinel for "flag omitted" in this `Int`-only XML bean — [resolveMirroringOptionsState]
         * maps it back to a `null` [dev.acme.adbtoolbox.domain.mirroring.MirroringOptions.maxSize]/
         * [dev.acme.adbtoolbox.domain.mirroring.MirroringOptions.videoBitRateMbps]. Never itself a
         * valid value (both fields require a strictly positive value once set). */
        const val NO_LIMIT: Int = 0
    }
}
