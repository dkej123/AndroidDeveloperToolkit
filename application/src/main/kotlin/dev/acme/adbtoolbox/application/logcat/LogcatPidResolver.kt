package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.logcat.LogcatPidCommand
import dev.acme.adbtoolbox.domain.logcat.LogcatPidParser
import dev.acme.adbtoolbox.domain.logcat.LogcatPidResolution
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Bounded request/response default (ADR 0005); `pidof` is a one-shot call, not a stream. */
val DEFAULT_LOGCAT_PID_TIMEOUT: Duration = 10.seconds

/**
 * Resolves a package's running process id(s) via `pidof` (task 035,
 * `design/IMPLEMENTATION.md` §4). Stdout is always parsed by [LogcatPidParser] regardless of exit
 * code — devices commonly exit non-zero for "no matching process" with empty stdout, which must
 * read as [LogcatPidResolution.NoProcess], not a failure. Only a genuine transport-level problem
 * (timeout, cancellation, disconnect, unsupported call) becomes [LogcatPidResolution.Failed].
 */
class LogcatPidResolver(
    private val transport: AdbTransport,
    private val timeout: Duration = DEFAULT_LOGCAT_PID_TIMEOUT,
) {
    suspend fun resolve(serial: DeviceSerial, packageName: String): LogcatPidResolution {
        val result = transport.executeText(
            AdbDeviceRequest(serial, AdbOperation.Shell(LogcatPidCommand.pidOf(packageName)), timeout = timeout),
        )
        return when (val outcome = result.outcome) {
            is AdbOutcome.Completed -> LogcatPidParser.parse(result.stdout)
            is AdbOutcome.TimedOut -> LogcatPidResolution.Failed("Timed out")
            is AdbOutcome.Cancelled -> LogcatPidResolution.Failed("Cancelled")
            is AdbOutcome.TransportFailure -> LogcatPidResolution.Failed(outcome.reason)
            is AdbOutcome.Unsupported -> LogcatPidResolution.Failed(outcome.reason)
        }
    }
}
