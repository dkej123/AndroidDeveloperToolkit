package io.github.dkej123.devicecockpit.application.apps

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.apps.UninstallCommands
import io.github.dkej123.devicecockpit.domain.apps.UninstallResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val DEFAULT_UNINSTALL_TIMEOUT: Duration = 30.seconds

class UninstallUseCase(
    private val transport: AdbTransport,
    private val timeout: Duration = DEFAULT_UNINSTALL_TIMEOUT,
) {
    private data class Target(val serial: DeviceSerial, val packageName: String)
    private val guard = Mutex()
    private val inFlight = mutableSetOf<Target>()

    suspend fun uninstall(serial: DeviceSerial, packageName: String): UninstallResult {
        val target = Target(serial, packageName)
        if (!guard.withLock { inFlight.add(target) }) return UninstallResult.RejectedDuplicate
        try {
            return toResult(
                transport.executeText(
                    AdbDeviceRequest(serial, UninstallCommands.uninstall(packageName), timeout),
                ),
            )
        } finally {
            withContext(NonCancellable) { guard.withLock { inFlight.remove(target) } }
        }
    }

    private fun toResult(result: AdbTextResult): UninstallResult {
        val diagnostics = listOf(result.stdout, result.stderr)
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString("\n")
        return when (val outcome = result.outcome) {
            is AdbOutcome.Completed -> when {
                outcome.exitCode == null -> UninstallResult.Failure("Command completed with unknown exit status")
                isAlreadyMissing(diagnostics) -> UninstallResult.AlreadyMissing(diagnostics)
                outcome.exitCode != 0 -> UninstallResult.Failure(
                    diagnostics.ifBlank { "Command exited with code ${outcome.exitCode}" },
                )
                diagnostics.contains("failure", ignoreCase = true) -> UninstallResult.Failure(diagnostics)
                diagnostics.contains("success", ignoreCase = true) -> UninstallResult.Success
                else -> UninstallResult.Failure(diagnostics.ifBlank { "Uninstall did not report success" })
            }
            AdbOutcome.TimedOut -> UninstallResult.TimedOut
            AdbOutcome.Cancelled -> UninstallResult.Cancelled
            is AdbOutcome.TransportFailure -> UninstallResult.Failure(outcome.reason)
            is AdbOutcome.Unsupported -> UninstallResult.Failure(outcome.reason)
        }
    }

    private fun isAlreadyMissing(diagnostics: String): Boolean =
        diagnostics.contains("not installed", ignoreCase = true) ||
            diagnostics.contains("unknown package", ignoreCase = true)
}
