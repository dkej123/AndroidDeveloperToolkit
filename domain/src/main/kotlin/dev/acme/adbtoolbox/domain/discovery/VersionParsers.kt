package dev.acme.adbtoolbox.domain.discovery

private val ADB_VERSION_LINE = Regex("""(?im)^Version\s+([0-9][\w.\-]*)""")
private val ADB_BRIDGE_VERSION_LINE = Regex("""(?im)Android Debug Bridge version\s+([0-9][\w.\-]*)""")
private val SCRCPY_VERSION_LINE = Regex("""(?im)^scrcpy\s+([0-9][\w.\-]*)""")

/** Parses `adb version` output, preferring the platform-tools `Version` line over the older Android
 * Debug Bridge protocol version. Returns `null` for output with no recognizable version rather than
 * throwing — an unparseable version is a [DiscoveryError.VersionQueryFailed], not a crash. */
fun parseAdbVersionOutput(output: String): ToolVersion? {
    val match = ADB_VERSION_LINE.find(output) ?: ADB_BRIDGE_VERSION_LINE.find(output)
    return match?.groupValues?.get(1)?.let(ToolVersion::of)
}

/** Parses `scrcpy --version` output's leading `scrcpy <version>` line. */
fun parseScrcpyVersionOutput(output: String): ToolVersion? {
    val match = SCRCPY_VERSION_LINE.find(output)
    return match?.groupValues?.get(1)?.let(ToolVersion::of)
}
