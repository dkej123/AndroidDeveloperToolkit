package dev.acme.adbtoolbox.intellij.persistence

import dev.acme.adbtoolbox.domain.nav.NavigationPersistence
import dev.acme.adbtoolbox.domain.nav.ViewId

/**
 * Resolves [state]'s persisted route key to a [ViewId], gracefully degrading to `null` ("no
 * persisted view — caller falls back to its default") for missing (default/never-persisted),
 * corrupt (blank, or a route key no [ViewId] owns), or older/newer-schema data — never throwing
 * (task 012, same contract as [resolvePersistedSerial]). Kept as a plain function, separate from
 * [NavigationPersistenceAdapter], so it is unit-testable without any IntelliJ Platform dependency
 * beyond the plain [NavigationPersistenceState] bean.
 */
internal fun resolvePersistedViewId(state: NavigationPersistenceState): ViewId? {
    if (state.schemaVersion != NavigationPersistenceState.CURRENT_SCHEMA_VERSION) return null
    val raw = state.lastView ?: return null
    if (raw.isBlank()) return null
    return ViewId.fromRouteKey(raw)
}

/**
 * The `:intellij` [NavigationPersistence] adapter (ADR 0006, task 012): reads/writes only the
 * [NavigationPersistenceState] slice of [projectState], never another feature's.
 * `getState()`/`loadState()` are synchronous by IntelliJ Platform design, so
 * [readLastViewNow]/[writeLastViewNow] are plain (non-`suspend`) functions — the `suspend` port
 * methods are trivial delegations, with no actual suspension, matching
 * [DeviceSelectionPersistenceAdapter]'s pattern (task 009's headless-sandbox `suspend`-across-module
 * hang workaround).
 */
class NavigationPersistenceAdapter(
    private val projectState: AdbToolboxProjectState,
) : NavigationPersistence {

    override suspend fun readLastView(): ViewId? = readLastViewNow()

    override suspend fun writeLastView(viewId: ViewId) = writeLastViewNow(viewId)

    internal fun readLastViewNow(): ViewId? = resolvePersistedViewId(projectState.state.navigation)

    internal fun writeLastViewNow(viewId: ViewId) {
        projectState.state.navigation = NavigationPersistenceState().apply {
            lastView = viewId.routeKey
        }
    }
}
