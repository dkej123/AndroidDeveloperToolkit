package dev.acme.adbtoolbox.domain.display

import dev.acme.adbtoolbox.domain.adb.AdbOutcome

/**
 * The result of a Display quick-toggle parser turning one `settings get`/`cmd ... night` call's
 * full [dev.acme.adbtoolbox.domain.adb.AdbTextResult] into a typed value (task 028). Distinguishes
 * every case a caller must handle explicitly, per adb-development's "handle non-zero exit codes
 * and malformed/unexpected output without crashing the caller":
 * - [Value] — stdout parsed to a well-formed value.
 * - [NotSet] — the device answered with the `settings`/`cmd` "not set" sentinel (`null` literal, or
 *   an "unknown command" style response for `cmd uimode night` on API levels that don't support it)
 *   — a real, device-reported "this setting doesn't apply here," not a parse failure.
 * - [PermissionDenied] — the device refused the call (stderr carries a permission/security denial).
 * - [Malformed] — stdout was neither a recognizable value nor the "not set" sentinel.
 * - [TransportFailed] — the call never produced parseable stdout at all: timed out, was cancelled,
 *   the transport couldn't reach the device, or the transport has no equivalent for this operation.
 *   Wraps the raw [AdbOutcome] rather than re-deriving those cases.
 */
sealed interface DisplaySettingRead<out T> {
    data class Value<out T>(val value: T) : DisplaySettingRead<T>

    data object NotSet : DisplaySettingRead<Nothing>

    data class PermissionDenied(val message: String) : DisplaySettingRead<Nothing>

    data class Malformed(val raw: String, val reason: String) : DisplaySettingRead<Nothing>

    data class TransportFailed(val outcome: AdbOutcome) : DisplaySettingRead<Nothing>
}

/** True when [AdbOutcome] reached the device and produced stdout worth parsing. */
internal fun AdbOutcome.reachedDevice(): Boolean = this is AdbOutcome.Completed

/** True when stderr looks like a permission/security denial rather than ordinary command output. */
internal fun String.looksLikePermissionDenial(): Boolean {
    val lower = lowercase()
    return lower.contains("permission denial") || lower.contains("securityexception")
}
