package dev.acme.adbtoolbox.domain.wifi

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult

/**
 * The normalized result of one `adb pair`/`adb connect` call, mirroring
 * [dev.acme.adbtoolbox.domain.adb.AdbOutcome]'s explicit-case shape (ADR 0005) rather than
 * collapsing timeout/cancellation into a generic failure.
 */
sealed interface WifiPairingStageResult {
    data object Success : WifiPairingStageResult
    data class Failure(val reason: String) : WifiPairingStageResult
    data object TimedOut : WifiPairingStageResult
    data object Cancelled : WifiPairingStageResult
}

/** `adb pair` reports success via stdout/stderr text (e.g. "Successfully paired to ..."), not a dedicated field. */
fun parsePairStageResult(result: AdbTextResult): WifiPairingStageResult = toStageResult(result, successMarker = "success")

/** `adb connect` reports success via "connected to ..." text. */
fun parseConnectStageResult(result: AdbTextResult): WifiPairingStageResult = toStageResult(result, successMarker = "connected to")

private fun toStageResult(result: AdbTextResult, successMarker: String): WifiPairingStageResult {
    val diagnostics = listOf(result.stdout, result.stderr)
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString("\n")
    return when (val outcome = result.outcome) {
        is AdbOutcome.Completed -> when {
            outcome.exitCode != null && outcome.exitCode != 0 ->
                WifiPairingStageResult.Failure(diagnostics.ifBlank { "Command exited with code ${outcome.exitCode}" })
            diagnostics.contains(successMarker, ignoreCase = true) -> WifiPairingStageResult.Success
            else -> WifiPairingStageResult.Failure(diagnostics.ifBlank { "Unexpected output" })
        }
        AdbOutcome.TimedOut -> WifiPairingStageResult.TimedOut
        AdbOutcome.Cancelled -> WifiPairingStageResult.Cancelled
        is AdbOutcome.TransportFailure -> WifiPairingStageResult.Failure(outcome.reason)
        is AdbOutcome.Unsupported -> WifiPairingStageResult.Failure(outcome.reason)
    }
}
