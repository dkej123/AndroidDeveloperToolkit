package dev.acme.adbtoolbox.domain.network

/**
 * A bounded, deduplicated, most-recently-used list of proxy endpoints a user has successfully
 * enabled (task 032, `design/README.md` §6's "Recent" section). Pure and platform-free so the
 * ordering/dedup/bound rules are testable without any persistence adapter: re-adding an endpoint
 * already present moves it to the front rather than duplicating it, and the list never grows past
 * [MAX_RECENTS] — the oldest entry is dropped first.
 */
data class RecentProxyEndpoints(val endpoints: List<ProxyEndpoint> = emptyList()) {

    /** Returns a copy with [endpoint] moved to the front, deduplicated, and capped at [MAX_RECENTS]. */
    fun withMostRecent(endpoint: ProxyEndpoint): RecentProxyEndpoints =
        RecentProxyEndpoints((listOf(endpoint) + endpoints.filterNot { it == endpoint }).take(MAX_RECENTS))

    companion object {
        const val MAX_RECENTS: Int = 5
    }
}
