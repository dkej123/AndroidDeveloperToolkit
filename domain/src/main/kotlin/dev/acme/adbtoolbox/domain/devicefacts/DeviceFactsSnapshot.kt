package dev.acme.adbtoolbox.domain.devicefacts

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * One immutable, exact-serial-scoped combination of every [DeviceFactId]'s current [DeviceFactState]
 * (task 015, mirroring [dev.acme.adbtoolbox.domain.devicecontext.DeviceContextSnapshot]'s
 * exact-serial-scoping discipline so a snapshot can never mix facts fetched for two different
 * devices).
 */
data class DeviceFactsSnapshot(
    val serial: DeviceSerial,
    val facts: Map<DeviceFactId, DeviceFactState>,
) {
    /** Every fact has settled (succeeded or failed) — none are still [DeviceFactState.Loading]. */
    val isSettled: Boolean
        get() = DeviceFactId.entries.all { facts[it] !is DeviceFactState.Loading && facts[it] != null }

    companion object {
        /** The initial snapshot for [serial]: every fact starts [DeviceFactState.Loading]. */
        fun loading(serial: DeviceSerial): DeviceFactsSnapshot =
            DeviceFactsSnapshot(serial, DeviceFactId.entries.associateWith { DeviceFactState.Loading })
    }
}
