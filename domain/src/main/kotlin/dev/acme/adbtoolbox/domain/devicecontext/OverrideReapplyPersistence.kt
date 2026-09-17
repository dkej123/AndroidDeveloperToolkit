package dev.acme.adbtoolbox.domain.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * Persists only the re-apply *offer* material (task 041's scope: "persist only approved re-apply
 * intent/state") — the last device-truth override values a serial carried while online, captured
 * at the moment it went offline so a reconnect (even across an IDE restart) can still offer, never
 * silently apply, to restore them. Actually mutating a device only ever happens after an explicit
 * accept through [dev.acme.adbtoolbox.application.devicecontext.OverrideResetCoordinator] — this
 * store never causes a write by itself. This is deliberately not a general "observed
 * contributions" store: live per-serial override state for the *currently selected* device keeps
 * coming from task 014's [OverrideSummaryContributor]/aggregator; only the disconnect-time
 * snapshot a re-apply offer needs crosses this persistence boundary. The concrete adapter
 * (`:intellij`) must never let missing/corrupt/older-schema data throw — it degrades to an empty
 * map instead, mirroring [dev.acme.adbtoolbox.domain.device.DeviceSelectionPersistence]'s contract.
 */
interface OverrideReapplyPersistence {
    suspend fun read(): Map<DeviceSerial, List<PendingReapplyOverride>>
    suspend fun write(pending: Map<DeviceSerial, List<PendingReapplyOverride>>)
}
