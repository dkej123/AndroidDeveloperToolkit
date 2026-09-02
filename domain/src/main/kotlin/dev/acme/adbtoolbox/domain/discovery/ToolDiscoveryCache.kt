package dev.acme.adbtoolbox.domain.discovery

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An explicit-invalidation cache of resolved [DiscoveredTool]s, keyed by [ToolId]. Never expires on
 * its own — a cached entry is used until [invalidate]/[invalidateAll] is called (e.g. after a
 * Settings path change, task 038, or a user-triggered re-check), matching this task's "explicit
 * cache + invalidation mechanism" requirement rather than a time-based heuristic.
 */
class ToolDiscoveryCache {
    private val mutex = Mutex()
    private val entries = mutableMapOf<ToolId, DiscoveredTool>()

    suspend fun get(toolId: ToolId): DiscoveredTool? = mutex.withLock { entries[toolId] }

    suspend fun put(toolId: ToolId, tool: DiscoveredTool) {
        mutex.withLock { entries[toolId] = tool }
    }

    suspend fun invalidate(toolId: ToolId) {
        mutex.withLock { entries.remove(toolId) }
    }

    suspend fun invalidateAll() {
        mutex.withLock { entries.clear() }
    }
}
