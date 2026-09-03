package dev.acme.adbtoolbox.domain.device

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import kotlinx.coroutines.delay

/**
 * A deterministic [DeviceSelectionPersistence] test double. [readDelayMillis]/[writeDelayMillis]
 * let tests simulate a slow read/write (via a virtual-time test dispatcher) to exercise ordering —
 * e.g. an in-flight write that must not clobber a more recent one. [readFailure], when set, makes
 * [readSelectedSerial] throw once (then clears) to exercise the recoverable-error path.
 */
class FakeDeviceSelectionPersistence(
    initial: DeviceSerial? = null,
    private val readDelayMillis: Long = 0,
    private val writeDelayMillis: Long = 0,
) : DeviceSelectionPersistence {

    private var stored: DeviceSerial? = initial
    var readFailure: Throwable? = null

    private val _writes = mutableListOf<DeviceSerial?>()
    val writes: List<DeviceSerial?> get() = _writes

    private val _writeCompletions = mutableListOf<DeviceSerial?>()
    val writeCompletions: List<DeviceSerial?> get() = _writeCompletions

    override suspend fun readSelectedSerial(): DeviceSerial? {
        if (readDelayMillis > 0) delay(readDelayMillis)
        readFailure?.let {
            readFailure = null
            throw it
        }
        return stored
    }

    override suspend fun writeSelectedSerial(serial: DeviceSerial?) {
        _writes += serial
        if (writeDelayMillis > 0) delay(writeDelayMillis)
        stored = serial
        _writeCompletions += serial
    }
}
