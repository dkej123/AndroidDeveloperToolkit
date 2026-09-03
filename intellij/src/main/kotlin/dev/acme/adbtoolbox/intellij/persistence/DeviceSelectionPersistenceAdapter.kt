package dev.acme.adbtoolbox.intellij.persistence

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceSelectionPersistence

/**
 * Resolves [state]'s persisted serial to a [DeviceSerial], gracefully degrading to `null` ("no
 * selection") for missing (default/never-persisted), corrupt (blank), or older/newer-schema data —
 * never throwing (task 009: a schema change must not crash on old persisted XML). Kept as a plain
 * function, separate from [DeviceSelectionPersistenceAdapter], so it is unit-testable without any
 * IntelliJ Platform dependency beyond the plain [DeviceSelectionState] bean.
 */
internal fun resolvePersistedSerial(state: DeviceSelectionState): DeviceSerial? {
    if (state.schemaVersion != DeviceSelectionState.CURRENT_SCHEMA_VERSION) return null
    val raw = state.selectedDeviceSerial ?: return null
    if (raw.isBlank()) return null
    return runCatching { DeviceSerial.of(raw) }.getOrNull()
}

/**
 * The `:intellij` [DeviceSelectionPersistence] adapter (ADR 0006, task 009): reads/writes only the
 * [DeviceSelectionState] slice of [projectState], never another feature's. `getState()`/`loadState()`
 * are synchronous by IntelliJ Platform design, so [readSelectedSerialNow]/[writeSelectedSerialNow]
 * are plain (non-`suspend`) functions — the `suspend` port methods are trivial delegations, with no
 * actual suspension, so callers dispatched via [dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider]
 * never block a real I/O wait here.
 */
class DeviceSelectionPersistenceAdapter(
    private val projectState: AdbToolboxProjectState,
) : DeviceSelectionPersistence {

    override suspend fun readSelectedSerial(): DeviceSerial? = readSelectedSerialNow()

    override suspend fun writeSelectedSerial(serial: DeviceSerial?) = writeSelectedSerialNow(serial)

    internal fun readSelectedSerialNow(): DeviceSerial? = resolvePersistedSerial(projectState.state.deviceSelection)

    internal fun writeSelectedSerialNow(serial: DeviceSerial?) {
        projectState.state.deviceSelection = DeviceSelectionState().apply {
            selectedDeviceSerial = serial?.toString()
        }
    }
}
