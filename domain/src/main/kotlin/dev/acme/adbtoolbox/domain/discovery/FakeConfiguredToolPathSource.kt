package dev.acme.adbtoolbox.domain.discovery

/** A deterministic [ConfiguredToolPathSource] test double: returns whatever was last [set] for a
 * [ToolId], with no path set (`null`) by default. */
class FakeConfiguredToolPathSource : ConfiguredToolPathSource {
    private val paths = mutableMapOf<ToolId, String>()

    override suspend fun configuredPath(toolId: ToolId): String? = paths[toolId]

    fun set(toolId: ToolId, path: String?) {
        if (path == null) paths.remove(toolId) else paths[toolId] = path
    }
}
