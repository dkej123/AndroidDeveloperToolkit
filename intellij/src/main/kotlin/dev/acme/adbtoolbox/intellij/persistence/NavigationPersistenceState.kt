package dev.acme.adbtoolbox.intellij.persistence

/**
 * The navigation slice of [AdbToolboxProjectState] (ADR 0006: `lastView`, a feature-local state
 * carrier composed into the one root `PersistentStateComponent`, added as a sibling property next
 * to [DeviceSelectionState] rather than a new component). A plain mutable bean (public `var`s,
 * no-arg constructor) as IntelliJ's `XmlSerializer` requires.
 *
 * [schemaVersion] mirrors [DeviceSelectionState.schemaVersion]: lets [resolvePersistedViewId]
 * recognize XML written by a shape this class no longer matches and degrade to "no persisted view"
 * instead of misinterpreting stale data.
 */
class NavigationPersistenceState {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var lastView: String? = null

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
