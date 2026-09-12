package dev.acme.adbtoolbox.application.deviceactions

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.deviceactions.DeviceActionCommands
import dev.acme.adbtoolbox.domain.deviceactions.DeviceActionResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Bounded request/response default (ADR 0005); reboot/wake are one-shot calls, not streams. */
val DEFAULT_DEVICE_ACTION_TIMEOUT: Duration = 10.seconds

/**
 * Runs task 016's Reboot and Wake basic actions through the [AdbTransport] gateway
 * (`design/README.md` §3's Device-view action row). Every non-success [AdbOutcome] (non-zero/
 * unknown exit status is the only success-shaped one — `null`/`0` both count, per ADR 0005's "no
 * exit code to report" case — timeout, cancellation, transport/disconnect failure, and an
 * unsupported transport call) is preserved as a caller-readable [DeviceActionResult.Failure.reason]
 * rather than collapsed into a boolean.
 *
 * [serial] is taken as a parameter, never re-read from some "current device" source mid-call, so a
 * device action already in flight is never silently reattributed if the caller's selection changes
 * while this suspends — the same guarantee [dev.acme.adbtoolbox.application.capture.CaptureScreenshotUseCase]
 * gives its own [serial] parameter.
 */
class DeviceActionsUseCase(
    private val transport: AdbTransport,
    private val timeout: Duration = DEFAULT_DEVICE_ACTION_TIMEOUT,
) {
    suspend fun reboot(serial: DeviceSerial): DeviceActionResult = run(serial, DeviceActionCommands.reboot())

    suspend fun wake(serial: DeviceSerial): DeviceActionResult = run(serial, DeviceActionCommands.wake())

    private suspend fun run(serial: DeviceSerial, command: AdbShellCommand): DeviceActionResult {
        val result = transport.executeText(
            AdbDeviceRequest(serial, AdbOperation.Shell(command), timeout = timeout),
        )
        return when (val outcome = result.outcome) {
            is AdbOutcome.Completed ->
                if (outcome.exitCode == null || outcome.exitCode == 0) {
                    DeviceActionResult.Success
                } else {
                    DeviceActionResult.Failure("Command exited with code ${outcome.exitCode}")
                }
            is AdbOutcome.TimedOut -> DeviceActionResult.Failure("Timed out")
            is AdbOutcome.Cancelled -> DeviceActionResult.Failure("Cancelled")
            is AdbOutcome.TransportFailure -> DeviceActionResult.Failure(outcome.reason)
            is AdbOutcome.Unsupported -> DeviceActionResult.Failure(outcome.reason)
        }
    }
}
