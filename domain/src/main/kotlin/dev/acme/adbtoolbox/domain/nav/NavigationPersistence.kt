package dev.acme.adbtoolbox.domain.nav

/**
 * Persists the one `lastView` [ViewId] for a project across IDE restarts (ADR 0006: project scope,
 * a sibling slice of `AdbToolboxProjectState`, same shape as
 * [dev.acme.adbtoolbox.domain.device.DeviceSelectionPersistence]). The concrete adapter must never
 * let missing, corrupt, or older/newer-schema persisted data throw — it degrades to
 * [readLastView] returning `null` ("no persisted view, use the default") instead. A thrown
 * exception signals a genuine, unexpected failure, which callers treat as a recoverable
 * [NavigationState.Error] rather than a crash.
 */
interface NavigationPersistence {
    suspend fun readLastView(): ViewId?
    suspend fun writeLastView(viewId: ViewId)
}
