package dev.acme.adbtoolbox.domain.display.density

/**
 * The result of [parseDensityText] turning `wm density` stdout into a typed value. Kept as its own
 * sealed type (rather than the shared two-case `AdbParseResult`) because this parser must
 * distinguish more than "parsed or malformed": some devices/emulators lack `wm density` support
 * entirely ([Unsupported]) and some refuse the call for permission reasons ([PermissionDenied]) —
 * both are real, device-reported outcomes, not garbled text, and callers must handle each
 * explicitly rather than lumping them into a generic parse failure.
 */
sealed interface DensityParseResult {
    data class Parsed(val reading: DensityReading) : DensityParseResult

    data class Malformed(val raw: String, val reason: String) : DensityParseResult

    data class Unsupported(val raw: String) : DensityParseResult

    data class PermissionDenied(val raw: String) : DensityParseResult
}
