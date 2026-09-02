package dev.acme.adbtoolbox.domain.discovery

/** What, if anything, would let a user fix a [DiscoveryError] — a later task's presentation layer
 * (design/README.md's toast "Set path…" pattern, task 038) turns this into a concrete UI action.
 * This task defines the typed hint only; no visual presentation. */
sealed interface RecoveryAction {
    data class SetPath(val toolId: ToolId) : RecoveryAction

    data object None : RecoveryAction
}
