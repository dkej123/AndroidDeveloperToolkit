package dev.acme.adbtoolbox.domain.discovery

/** Static, platform-neutral facts about how to operate a [ToolId]: the arguments that make it print
 * its version, and how to parse that output back into a [ToolVersion]. This is the "capability"
 * surface tool discovery needs — decoupled from any process-execution or OS adapter concern. */
data class ToolDescriptor(
    val id: ToolId,
    val versionQueryArguments: List<String>,
    val parseVersion: (String) -> ToolVersion?,
)

object ToolDescriptors {
    val Adb = ToolDescriptor(ToolId.Adb, listOf("version"), ::parseAdbVersionOutput)
    val Scrcpy = ToolDescriptor(ToolId.Scrcpy, listOf("--version"), ::parseScrcpyVersionOutput)

    fun of(id: ToolId): ToolDescriptor = when (id) {
        ToolId.Adb -> Adb
        ToolId.Scrcpy -> Scrcpy
    }
}
