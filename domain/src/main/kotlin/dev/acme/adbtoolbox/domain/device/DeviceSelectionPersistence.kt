package dev.acme.adbtoolbox.domain.device

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * Persists the one selected-device serial for a project across IDE restarts (ADR 0006: project
 * scope, `selectedDeviceSerial`). The concrete adapter (a per-project
 * `PersistentStateComponent`, `:intellij`) must never let missing, corrupt, or older-schema
 * persisted data throw — it degrades to "no selection" ([readSelectedSerial] returning `null`)
 * instead. A thrown exception from either method signals a genuine, unexpected failure (e.g. an
 * I/O error), which callers treat as a recoverable [SelectedDeviceState.Error] rather than a crash.
 */
interface DeviceSelectionPersistence {
    suspend fun readSelectedSerial(): DeviceSerial?
    suspend fun writeSelectedSerial(serial: DeviceSerial?)
}
