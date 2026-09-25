package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.ClearDataCommands
import dev.acme.adbtoolbox.domain.apps.ClearDataResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Bounded request/response default (ADR 0005); `pm clear` is a one-shot call, not a stream. */
val DEFAULT_CLEAR_DATA_TIMEOUT: Duration = 10.seconds

/** A truthful `pm clear` failure body — case-insensitive, matched against the trimmed combined output. */
private const val FAILURE_MARKER = "failed"

/** `pm clear`'s whole output on success; decides the result when ddmlib reports no exit code. */
private const val SUCCESS_BODY = "success"

/**
 * Runs task 024's destructive Clear-data action (`pm clear <pkg>`) through the [AdbTransport]
 * gateway, for the exact `(serial, packageName)` pair passed to [clearData] — never an implicit
 * "current selection" read internally — mirroring
 * [dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase]'s parameter discipline. This class
 * issues the command unconditionally on every call: the destructive confirmation gate is entirely
 * the caller's responsibility (see
 * [dev.acme.adbtoolbox.application.apps.ClearDataViewModel]), never re-derived or re-checked here —
 * matching the ADBHelper-reference note that `pm clear`'s own response carries no confirmation of
 * any kind.
 *
 * **Truthful result handling**: `pm clear` can exit `0` while still reporting failure in its own
 * output body (`.claude/skills/adb-development/references/adbhelper.md`'s pointer to
 * `ClearAppDataReceiver`: "pm clear behavior and response shape") — a bare exit-code check alone
 * would silently coerce that into [ClearDataResult.Success]. [toResult] therefore also inspects the
 * combined stdout/stderr text for a `"failed"` marker (case-insensitive). A missing exit code is
 * kept as an explicit failure even if the body says `Success`, as required by ADR 0005.
 *
 * **Duplicate-in-flight guarding** mirrors [dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase]:
 * [guard] protects [inFlightTargets], keyed to the exact `(serial, packageName)` pair, so a
 * concurrent call for the same target is rejected as [ClearDataResult.RejectedDuplicate] while a
 * call for a *different* package or device is never blocked. Both APIs are Kotlin/coroutines APIs,
 * preserving the application's KMP-ready boundary (ADR 0002).
 */
class ClearDataUseCase(
    private val transport: AdbTransport,
    private val timeout: Duration = DEFAULT_CLEAR_DATA_TIMEOUT,
) {
    private data class Target(val serial: DeviceSerial, val packageName: String)

    private val guard = Mutex()
    private val inFlightTargets = mutableSetOf<Target>()

    suspend fun clearData(serial: DeviceSerial, packageName: String): ClearDataResult {
        val target = Target(serial, packageName)
        val accepted = guard.withLock { inFlightTargets.add(target) }
        if (!accepted) return ClearDataResult.RejectedDuplicate
        try {
            val result = transport.executeText(
                AdbDeviceRequest(serial, AdbOperation.Shell(ClearDataCommands.clear(packageName)), timeout = timeout),
            )
            return toResult(result)
        } finally {
            withContext(NonCancellable) {
                guard.withLock { inFlightTargets.remove(target) }
            }
        }
    }

    private fun toResult(result: AdbTextResult): ClearDataResult = when (val outcome = result.outcome) {
        is AdbOutcome.Completed -> {
            val combined = (result.stdout + result.stderr).trim()
            when {
                // ddmlib (shell v1) reports no exit code (ADR 0005): pm's own output decides.
                outcome.exitCode == null -> when {
                    combined.lowercase().contains(FAILURE_MARKER) -> ClearDataResult.Failure(combined)
                    combined.equals(SUCCESS_BODY, ignoreCase = true) -> ClearDataResult.Success
                    else -> ClearDataResult.Failure("Command completed with unknown exit status")
                }
                outcome.exitCode != 0 ->
                    ClearDataResult.Failure(combined.ifBlank { "Command exited with code ${outcome.exitCode}" })
                combined.lowercase().contains(FAILURE_MARKER) ->
                    ClearDataResult.Failure(combined.ifBlank { "pm clear reported failure" })
                else -> ClearDataResult.Success
            }
        }
        is AdbOutcome.TimedOut -> ClearDataResult.Failure("Timed out")
        is AdbOutcome.Cancelled -> ClearDataResult.Failure("Cancelled")
        is AdbOutcome.TransportFailure -> ClearDataResult.Failure(outcome.reason)
        is AdbOutcome.Unsupported -> ClearDataResult.Failure(outcome.reason)
    }
}
