package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.ConfiguredToolPathSource
import dev.acme.adbtoolbox.domain.discovery.ToolId
import java.util.concurrent.ConcurrentHashMap

/**
 * A non-persisted [ConfiguredToolPathSource]: holds a user-set path only for the lifetime of this
 * process. Task 038 (Settings) replaces or wraps this with a `PersistentStateComponent`-backed
 * adapter (ADR 0006) that survives IDE restarts; this task defines and exercises the port contract
 * only, so [DefaultToolLocator]-based composition has something concrete to depend on today.
 */
class InMemoryConfiguredToolPathSource : ConfiguredToolPathSource {
    private val paths = ConcurrentHashMap<ToolId, String>()

    override suspend fun configuredPath(toolId: ToolId): String? = paths[toolId]

    fun setConfiguredPath(toolId: ToolId, path: String?) {
        if (path == null) paths.remove(toolId) else paths[toolId] = path
    }
}
