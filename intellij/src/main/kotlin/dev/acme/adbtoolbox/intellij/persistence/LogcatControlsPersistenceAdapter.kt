package dev.acme.adbtoolbox.intellij.persistence

import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.domain.logcat.LogcatControlsPersistence
import dev.acme.adbtoolbox.domain.logcat.LogcatPersistedControls

/**
 * Resolves [state] to a domain [LogcatPersistedControls], gracefully degrading to
 * [LogcatPersistedControls]'s defaults for missing, corrupt, or older/newer-schema persisted data —
 * never throwing, mirroring [resolveNetworkRecents]'s contract. An unrecognized
 * [LogcatControlsPersistenceState.minSeverityName] (hand-edited XML, a future/removed enum
 * constant) degrades to `null` (no floor) rather than failing the whole read. Kept as a plain
 * function, separate from [LogcatControlsPersistenceAdapter], for the same IntelliJ-Platform-free
 * unit-testability reason [resolveNetworkRecents] is.
 */
internal fun resolveLogcatControlsState(state: LogcatControlsPersistenceState): LogcatPersistedControls {
    if (state.schemaVersion != LogcatControlsPersistenceState.CURRENT_SCHEMA_VERSION) return LogcatPersistedControls()
    val minSeverity = state.minSeverityName?.let { name ->
        runCatching { LogSeverity.valueOf(name) }.getOrNull()
    }
    return LogcatPersistedControls(
        minSeverity = minSeverity,
        packageFilterOn = state.packageFilterOn,
        wrap = state.wrap,
    )
}

/**
 * The `:intellij` [LogcatControlsPersistence] adapter (task 037), matching
 * [NetworkPersistenceAdapter]'s synchronous-`PersistentStateComponent`-behind-a-`suspend`-port
 * pattern: reads/writes only the [LogcatControlsPersistenceState] slice of [projectState].
 */
class LogcatControlsPersistenceAdapter(
    private val projectState: AdbToolboxProjectState,
) : LogcatControlsPersistence {

    override suspend fun read(): LogcatPersistedControls = readNow()

    override suspend fun write(controls: LogcatPersistedControls) = writeNow(controls)

    internal fun readNow(): LogcatPersistedControls = resolveLogcatControlsState(projectState.state.logcatControls)

    internal fun writeNow(controls: LogcatPersistedControls) {
        projectState.state.logcatControls = LogcatControlsPersistenceState().apply {
            minSeverityName = controls.minSeverity?.name
            packageFilterOn = controls.packageFilterOn
            wrap = controls.wrap
        }
    }
}
