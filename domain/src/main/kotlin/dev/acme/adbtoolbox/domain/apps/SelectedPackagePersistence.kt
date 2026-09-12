package dev.acme.adbtoolbox.domain.apps

/**
 * Persists the one selected-package [SelectedPackage] for a project across IDE restarts
 * (`design/README.md`'s State model: `selectedPackage`), mirroring
 * [dev.acme.adbtoolbox.domain.device.DeviceSelectionPersistence]'s contract. The concrete adapter
 * (`:intellij`) must never let missing, corrupt, or older-schema persisted data throw — it degrades
 * to "no selection" ([readSelectedPackage] returning `null`) instead. A thrown exception signals a
 * genuine, unexpected failure, which callers treat as a recoverable condition, never a crash.
 */
interface SelectedPackagePersistence {
    suspend fun readSelectedPackage(): SelectedPackage?
    suspend fun writeSelectedPackage(selection: SelectedPackage?)
}
