package io.github.dkej123.devicecockpit.application.logcat

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.logcat.LogcatPidCommand
import io.github.dkej123.devicecockpit.domain.logcat.LogcatPidParser
import io.github.dkej123.devicecockpit.domain.logcat.LogcatPidResolution
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
