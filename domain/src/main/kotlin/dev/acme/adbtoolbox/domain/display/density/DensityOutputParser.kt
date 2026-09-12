package dev.acme.adbtoolbox.domain.display.density

private val PHYSICAL_DENSITY_LINE = Regex("""(?i)physical\s*density\s*:\s*(\d+)""")
private val OVERRIDE_DENSITY_LINE = Regex("""(?i)override\s*density\s*:\s*(\d+)""")

/**
 * `wm density` output is device-worded free text, not a stable machine format: the standard AOSP
 * shape is two lines ("Physical density: <n>", "Override density: <n>" when overridden), but OEMs
 * are known to vary case/spacing and append trailing descriptive text. This parser is therefore
 * deliberately permissive about wording (case-insensitive, whitespace-tolerant, ignores trailing
 * text after the digits, tolerates CRLF line endings) while still refusing to guess when the
 * output does not look like density output at all.
 */
fun parseDensityText(raw: String): DensityParseResult {
    if (raw.looksLikeUnsupportedCommand()) return DensityParseResult.Unsupported(raw)
    if (raw.looksLikePermissionDenial()) return DensityParseResult.PermissionDenied(raw)

    val physicalMatch = PHYSICAL_DENSITY_LINE.find(raw)
        ?: return DensityParseResult.Malformed(raw, reason = "no \"Physical density\" line found")
    val physicalDpi = physicalMatch.groupValues[1].toIntOrNull()
        ?: return DensityParseResult.Malformed(raw, reason = "physical density value was not an integer")

    val overrideDpi = OVERRIDE_DENSITY_LINE.find(raw)?.groupValues?.get(1)?.toIntOrNull()

    return DensityParseResult.Parsed(DensityReading(physicalDpi, overrideDpi))
}

private fun String.looksLikeUnsupportedCommand(): Boolean {
    val lower = lowercase()
    return lower.contains("unknown command") || lower.contains("no such command")
}

private fun String.looksLikePermissionDenial(): Boolean {
    val lower = lowercase()
    return lower.contains("permission denial") || lower.contains("securityexception")
}
