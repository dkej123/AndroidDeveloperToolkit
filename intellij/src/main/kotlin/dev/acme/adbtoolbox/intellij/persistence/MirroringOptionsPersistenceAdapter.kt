package dev.acme.adbtoolbox.intellij.persistence

import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsRepository

/** The pre-versioning shape [migrateMirroringOptionsState] still understands and upgrades in place,
 * matching [LEGACY_SETTINGS_SCHEMA_VERSION_V0]'s seam. */
internal const val LEGACY_MIRRORING_OPTIONS_SCHEMA_VERSION_V0: Int = 0

/**
 * Upgrades [state] to [MirroringOptionsPersistenceState.CURRENT_SCHEMA_VERSION] in place, or returns
 * `null` if [state]'s version is not one this code knows how to upgrade — matching
 * [migrateSettingsState]'s dispatch shape.
 */
internal fun migrateMirroringOptionsState(state: MirroringOptionsPersistenceState): MirroringOptionsPersistenceState? =
    when (state.schemaVersion) {
        MirroringOptionsPersistenceState.CURRENT_SCHEMA_VERSION -> state
        LEGACY_MIRRORING_OPTIONS_SCHEMA_VERSION_V0 -> migrateV0ToV1(state)
        else -> null
    }

private fun migrateV0ToV1(legacy: MirroringOptionsPersistenceState): MirroringOptionsPersistenceState =
    MirroringOptionsPersistenceState().apply {
        schemaVersion = MirroringOptionsPersistenceState.CURRENT_SCHEMA_VERSION
        stayAwake = legacy.stayAwake
        showTouches = legacy.showTouches
        maxSize = legacy.maxSize
        videoBitRateMbps = legacy.videoBitRateMbps
    }

/**
 * Resolves [state] to a domain [MirroringOptions], gracefully degrading field-by-field for missing,
 * out-of-range, or [MirroringOptionsPersistenceState.NO_LIMIT]-sentinel values, and to
 * [MirroringOptions.DEFAULT] entirely for a schema version [migrateMirroringOptionsState] cannot
 * upgrade — never throwing, matching [resolveSettingsState]'s contract. Kept as a plain function,
 * separate from [MirroringOptionsPersistenceAdapter], for the same IntelliJ-Platform-free
 * unit-testability reason [resolveSettingsState] is.
 */
internal fun resolveMirroringOptionsState(state: MirroringOptionsPersistenceState): MirroringOptions {
    val migrated = migrateMirroringOptionsState(state) ?: return MirroringOptions.DEFAULT

    val maxSize = migrated.maxSize.takeIf {
        it in MirroringOptions.MIN_MAX_SIZE_PX..MirroringOptions.MAX_MAX_SIZE_PX
    }
    val videoBitRateMbps = migrated.videoBitRateMbps.takeIf {
        it in MirroringOptions.MIN_VIDEO_BIT_RATE_MBPS..MirroringOptions.MAX_VIDEO_BIT_RATE_MBPS
    }

    return MirroringOptions(
        stayAwake = migrated.stayAwake,
        showTouches = migrated.showTouches,
        maxSize = maxSize,
        videoBitRateMbps = videoBitRateMbps,
    )
}

/**
 * The `:intellij` [MirroringOptionsRepository] adapter (task 040), matching
 * [SettingsPersistenceAdapter]'s synchronous-`PersistentStateComponent`-behind-a-`suspend`-port
 * pattern: reads/writes only the [MirroringOptionsPersistenceState] slice of [projectState].
 */
class MirroringOptionsPersistenceAdapter(
    private val projectState: AdbToolboxProjectState,
) : MirroringOptionsRepository {

    override suspend fun readOptions(): MirroringOptions = readOptionsNow()

    override suspend fun writeOptions(options: MirroringOptions) = writeOptionsNow(options)

    internal fun readOptionsNow(): MirroringOptions = resolveMirroringOptionsState(projectState.state.mirroringOptions)

    internal fun writeOptionsNow(options: MirroringOptions) {
        projectState.state.mirroringOptions = MirroringOptionsPersistenceState().apply {
            stayAwake = options.stayAwake
            showTouches = options.showTouches
            maxSize = options.maxSize ?: MirroringOptionsPersistenceState.NO_LIMIT
            videoBitRateMbps = options.videoBitRateMbps ?: MirroringOptionsPersistenceState.NO_LIMIT
        }
    }
}
