package dev.acme.adbtoolbox.domain.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * A deterministic [OverrideReapplyPersistence] test double, mirroring
 * [dev.acme.adbtoolbox.domain.network.FakeNetworkRecentsPersistence]. [readFailure]/[writeFailure],
 * when set, make the next call throw once (then clear) to exercise the recoverable-error path.
 */
class FakeOverrideReapplyPersistence(
    initial: Map<DeviceSerial, List<PendingReapplyOverride>> = emptyMap(),
) : OverrideReapplyPersistence {

    private var stored: Map<DeviceSerial, List<PendingReapplyOverride>> = initial
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null

    private val _writes = mutableListOf<Map<DeviceSerial, List<PendingReapplyOverride>>>()
    val writes: List<Map<DeviceSerial, List<PendingReapplyOverride>>> get() = _writes

    override suspend fun read(): Map<DeviceSerial, List<PendingReapplyOverride>> {
        readFailure?.let {
            readFailure = null
            throw it
        }
        return stored
    }

    override suspend fun write(pending: Map<DeviceSerial, List<PendingReapplyOverride>>) {
        writeFailure?.let {
            writeFailure = null
            throw it
        }
        _writes += pending
        stored = pending
    }
}
