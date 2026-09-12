package dev.acme.adbtoolbox.intellij.persistence

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackagePersistence

/**
 * Resolves [state]'s persisted serial/package pair to a [SelectedPackage], gracefully degrading to
 * `null` ("no selection") for missing (default/never-persisted), corrupt (blank serial or package
 * name), or older/newer-schema data — never throwing (mirrors [resolvePersistedSerial]'s contract).
 * Kept as a plain function, separate from [AppsSelectionPersistenceAdapter], so it is unit-testable
 * without any IntelliJ Platform dependency beyond the plain [AppsSelectionState] bean.
 */
internal fun resolveAppsSelection(state: AppsSelectionState): SelectedPackage? {
    if (state.schemaVersion != AppsSelectionState.CURRENT_SCHEMA_VERSION) return null
    val rawSerial = state.selectedDeviceSerial ?: return null
    if (rawSerial.isBlank()) return null
    val rawPackageName = state.selectedPackageName ?: return null
    if (rawPackageName.isBlank()) return null
    val serial = runCatching { DeviceSerial.of(rawSerial) }.getOrNull() ?: return null
    return SelectedPackage(serial, rawPackageName)
}

/**
 * The `:intellij` [SelectedPackagePersistence] adapter (task 022): reads/writes only the
 * [AppsSelectionState] slice of [projectState], never another feature's — mirrors
 * [DeviceSelectionPersistenceAdapter]. `getState()`/`loadState()` are synchronous by IntelliJ
 * Platform design, so [readSelectedPackageNow]/[writeSelectedPackageNow] are plain (non-`suspend`)
 * functions — the `suspend` port methods are trivial delegations, with no actual suspension.
 */
class AppsSelectionPersistenceAdapter(
    private val projectState: AdbToolboxProjectState,
) : SelectedPackagePersistence {

    override suspend fun readSelectedPackage(): SelectedPackage? = readSelectedPackageNow()

    override suspend fun writeSelectedPackage(selection: SelectedPackage?) = writeSelectedPackageNow(selection)

    internal fun readSelectedPackageNow(): SelectedPackage? = resolveAppsSelection(projectState.state.apps)

    internal fun writeSelectedPackageNow(selection: SelectedPackage?) {
        projectState.state.apps = AppsSelectionState().apply {
            selectedDeviceSerial = selection?.serial?.toString()
            selectedPackageName = selection?.packageName
        }
    }
}
