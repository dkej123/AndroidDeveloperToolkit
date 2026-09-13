package dev.acme.adbtoolbox.intellij.persistence

import dev.acme.adbtoolbox.domain.settings.SettingsRepository
import dev.acme.adbtoolbox.domain.settings.SettingsState

/** The pre-versioning shape [migrateSettingsState] still understands and upgrades in place, rather
 * than discarding (task 038's migration requirement). There is no real persisted data at this
 * version today — this constant exists so the migration *mechanism* (a versioned upgrade chain,
 * not just a version-equality check) has a concrete seam to exercise and extend the next time this
 * bean's shape changes incompatibly. */
internal const val LEGACY_SETTINGS_SCHEMA_VERSION_V0: Int = 0

/**
 * Upgrades [state] to [SettingsPersistenceState.CURRENT_SCHEMA_VERSION] in place, or returns `null`
 * if [state]'s version is not one this code knows how to upgrade (a version newer than
 * [SettingsPersistenceState.CURRENT_SCHEMA_VERSION], which by definition this build cannot
 * understand). Unlike [resolvePersistedSerial]/[resolvePersistedViewId]'s "unknown version -> lose
 * the data", a *recognized* older version's fields are carried forward — today, [LEGACY_SETTINGS_SCHEMA_VERSION_V0]
 * has the same field shape as the current version, so its migration step is a value copy plus a
 * version bump; a future incompatible rename/removal would add its own `migrateVxToVy` step here
 * without touching this function's dispatch shape.
 */
internal fun migrateSettingsState(state: SettingsPersistenceState): SettingsPersistenceState? =
    when (state.schemaVersion) {
        SettingsPersistenceState.CURRENT_SCHEMA_VERSION -> state
        LEGACY_SETTINGS_SCHEMA_VERSION_V0 -> migrateV0ToV1(state)
        else -> null
    }

private fun migrateV0ToV1(legacy: SettingsPersistenceState): SettingsPersistenceState =
    SettingsPersistenceState().apply {
        schemaVersion = SettingsPersistenceState.CURRENT_SCHEMA_VERSION
        adbPathOverride = legacy.adbPathOverride
        scrcpyPathOverride = legacy.scrcpyPathOverride
        captureDirectory = legacy.captureDirectory
        logcatBufferSizeKb = legacy.logcatBufferSizeKb
    }

/**
 * Resolves [state] to a domain [SettingsState], gracefully degrading field-by-field for missing
 * (default/never-persisted), blank, or out-of-range values, and to [SettingsState.DEFAULT] entirely
 * for a schema version [migrateSettingsState] cannot upgrade — never throwing (task 038, same
 * contract as [resolvePersistedSerial]/[resolvePersistedViewId]). Kept as a plain function,
 * separate from [SettingsPersistenceAdapter], so it is unit-testable without any IntelliJ Platform
 * dependency beyond the plain [SettingsPersistenceState] bean.
 */
internal fun resolveSettingsState(state: SettingsPersistenceState): SettingsState {
    val migrated = migrateSettingsState(state) ?: return SettingsState.DEFAULT

    val bufferSize = migrated.logcatBufferSizeKb.takeIf {
        it in SettingsState.MIN_LOGCAT_BUFFER_SIZE_KB..SettingsState.MAX_LOGCAT_BUFFER_SIZE_KB
    } ?: SettingsState.DEFAULT_LOGCAT_BUFFER_SIZE_KB

    return SettingsState(
        adbPathOverride = migrated.adbPathOverride?.trim()?.takeIf { it.isNotBlank() },
        scrcpyPathOverride = migrated.scrcpyPathOverride?.trim()?.takeIf { it.isNotBlank() },
        captureDirectory = migrated.captureDirectory?.trim()?.takeIf { it.isNotBlank() },
        logcatBufferSizeKb = bufferSize,
    )
}

/**
 * The `:intellij` [SettingsRepository] adapter (ADR 0006, task 038): reads/writes only the
 * [SettingsPersistenceState] slice of [projectState], never another feature's.
 * `getState()`/`loadState()` are synchronous by IntelliJ Platform design, so
 * [readSettingsNow]/[writeSettingsNow] are plain (non-`suspend`) functions — the `suspend` port
 * methods are trivial delegations, matching [DeviceSelectionPersistenceAdapter]/
 * [NavigationPersistenceAdapter]'s pattern (task 009's headless-sandbox `suspend`-across-module hang
 * workaround).
 */
class SettingsPersistenceAdapter(
    private val projectState: AdbToolboxProjectState,
) : SettingsRepository {

    override suspend fun readSettings(): SettingsState = readSettingsNow()

    override suspend fun writeSettings(state: SettingsState) = writeSettingsNow(state)

    internal fun readSettingsNow(): SettingsState = resolveSettingsState(projectState.state.settings)

    internal fun writeSettingsNow(state: SettingsState) {
        projectState.state.settings = SettingsPersistenceState().apply {
            adbPathOverride = state.adbPathOverride
            scrcpyPathOverride = state.scrcpyPathOverride
            captureDirectory = state.captureDirectory
            logcatBufferSizeKb = state.logcatBufferSizeKb
        }
    }
}
