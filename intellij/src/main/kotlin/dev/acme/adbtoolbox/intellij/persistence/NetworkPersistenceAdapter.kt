package dev.acme.adbtoolbox.intellij.persistence

import dev.acme.adbtoolbox.domain.network.NetworkRecentsPersistence
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyReadState
import dev.acme.adbtoolbox.domain.network.parseProxyReadback

/**
 * Resolves [state]'s persisted `host:port` strings to [ProxyEndpoint]s, gracefully degrading to an
 * empty list for missing (default/never-persisted), corrupt, or older/newer-schema data — never
 * throwing (mirrors [resolvePersistedSerial]'s contract). A corrupt individual entry (one that no
 * longer parses, e.g. hand-edited XML) is silently dropped rather than failing the whole list, since
 * the remaining entries are still valid recents. Reuses [parseProxyReadback] (task 030) rather than
 * duplicating its host/port-split parsing rules. Kept as a plain function, separate from
 * [NetworkPersistenceAdapter], so it is unit-testable without any IntelliJ Platform dependency
 * beyond the plain [NetworkState] bean.
 */
internal fun resolveNetworkRecents(state: NetworkState): List<ProxyEndpoint> {
    if (state.schemaVersion != NetworkState.CURRENT_SCHEMA_VERSION) return emptyList()
    return state.recentEndpoints.mapNotNull { raw ->
        (parseProxyReadback(raw) as? ProxyReadState.Active)?.endpoint
    }
}

/**
 * The `:intellij` [NetworkRecentsPersistence] adapter (task 032, ADR 0006): reads/writes only the
 * [NetworkState] slice of [projectState], never another feature's — mirrors
 * [DeviceSelectionPersistenceAdapter]/[AppsSelectionPersistenceAdapter]. `getState()`/`loadState()`
 * are synchronous by IntelliJ Platform design, so [readRecentsNow]/[writeRecentsNow] are plain
 * (non-`suspend`) functions — the `suspend` port methods are trivial delegations, with no actual
 * suspension.
 */
class NetworkPersistenceAdapter(
    private val projectState: AdbToolboxProjectState,
) : NetworkRecentsPersistence {

    override suspend fun readRecents(): List<ProxyEndpoint> = readRecentsNow()

    override suspend fun writeRecents(recents: List<ProxyEndpoint>) = writeRecentsNow(recents)

    internal fun readRecentsNow(): List<ProxyEndpoint> = resolveNetworkRecents(projectState.state.network)

    internal fun writeRecentsNow(recents: List<ProxyEndpoint>) {
        projectState.state.network = NetworkState().apply {
            recentEndpoints = recents.map { it.render() }.toMutableList()
        }
    }
}
