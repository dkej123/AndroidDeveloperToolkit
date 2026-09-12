package dev.acme.adbtoolbox.domain.packages

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import kotlinx.coroutines.flow.StateFlow

/**
 * Observable, serial-scoped package discovery (task 021): the single source of truth downstream
 * Apps (task 022+) and Logcat (task 033+, PID-to-package lookup) code depends on, kept as a
 * `:domain` port so those features depend on this contract rather than on `:adapters-adb`'s
 * `AdbPackageRepository` directly (mirrors [dev.acme.adbtoolbox.domain.device.DeviceRepository]).
 *
 * [refresh] is fire-and-forget: it requests discovery for [serial]/[scope] and returns immediately,
 * with [state] observing the result. Calling [refresh] again — for the same or a different serial —
 * supersedes any in-flight discovery; a slow response belonging to a superseded request must never
 * be applied to [state] (stale-result suppression).
 */
interface PackageRepository {
    val state: StateFlow<PackageListState>

    fun refresh(serial: DeviceSerial, scope: PackageListScope)
}
