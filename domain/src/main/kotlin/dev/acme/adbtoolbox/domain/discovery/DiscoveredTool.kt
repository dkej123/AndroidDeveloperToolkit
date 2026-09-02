package dev.acme.adbtoolbox.domain.discovery

/** A tool successfully resolved and version-confirmed: [path] passed the adapter's executable-file
 * check and [version] was parsed from a real `version`/`--version` invocation, not assumed. */
data class DiscoveredTool(
    val id: ToolId,
    val source: ToolSource,
    val path: ToolExecutablePath,
    val version: ToolVersion,
)
