package dev.acme.adbtoolbox.domain.logcat

/**
 * The project-scoped, persisted subset of Logcat controls (task 037, ADR 0006 / `design/README.md`'s
 * state model: `logMinLevel`, `logPackageFilterOn`, `logWrap`). [query]/pause/follow are runtime-only
 * (not part of this state model entry) so they are deliberately absent here — only the three fields
 * the design explicitly persists are carried.
 */
data class LogcatPersistedControls(
    val minSeverity: LogSeverity? = null,
    val packageFilterOn: Boolean = true,
    val wrap: Boolean = false,
)

/**
 * Persists [LogcatPersistedControls] across IDE restarts. The concrete adapter (`:intellij`) must
 * never let missing, corrupt, or older/newer-schema persisted data throw — it degrades to
 * [LogcatPersistedControls]'s defaults instead, mirroring
 * [dev.acme.adbtoolbox.domain.network.NetworkRecentsPersistence]'s contract. A thrown exception
 * signals a genuine, unexpected failure, which callers treat as recoverable, never a crash.
 */
interface LogcatControlsPersistence {
    suspend fun read(): LogcatPersistedControls
    suspend fun write(controls: LogcatPersistedControls)
}
