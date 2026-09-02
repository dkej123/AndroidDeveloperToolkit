package dev.acme.adbtoolbox.domain.adb

/**
 * The bounded, fully-collected result of an [AdbTransport.executeText] call: stdout, stderr, and
 * the terminal [AdbOutcome], preserved separately and never merged (adb-development: "preserve
 * stdout, stderr, and cancellation status; don't swallow or merge them").
 */
data class AdbTextResult(
    val outcome: AdbOutcome,
    val stdout: String,
    val stderr: String,
)
