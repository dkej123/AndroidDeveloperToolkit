package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.AppLifecycleCommands
import dev.acme.adbtoolbox.domain.apps.AppLifecycleResult
import dev.acme.adbtoolbox.domain.apps.AppRestartResult
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Bounded request/response default (ADR 0005); force-stop/launch are one-shot calls, not streams. */
val DEFAULT_APP_LIFECYCLE_TIMEOUT: Duration = 10.seconds

/** A `monkey` launch failure's stdout/stderr marker for "no launchable activity" (case-insensitive). */
private const val NO_LAUNCHER_MARKER = "no activities found"

/**
 * Runs task 023's Force-stop, Launch, and Restart app-lifecycle actions through the [AdbTransport]
 * gateway (`design/README.md` §4's Apps-view action footer), for the exact `(serial, packageName)`
 * pair passed to each call — never an implicit "current selection" read internally — mirroring
 * [dev.acme.adbtoolbox.application.deviceactions.DeviceActionsUseCase]'s parameter discipline.
 *
 * **Duplicate-in-flight guarding** is enforced *here*, at the use-case layer (task 023's scope:
 * "Prevent duplicate in-flight actions per package/serial"), not merely in a presenter — unlike
 * task 016's device-wide `busyAction` flag, this guard is keyed to the exact `(serial, packageName)`
 * pair, so an in-flight action against one package never blocks a concurrent action against a
 * *different* package or device. [inFlightKeys] is a thread-safe [MutableSet]; membership is
 * checked-and-added atomically via [MutableSet.add]'s own return value, so two concurrent calls for
 * the same key can never both proceed — the second always observes [AppLifecycleResult.RejectedDuplicate]
 * / [AppRestartResult.RejectedDuplicate].
 *
 * **Restart** ([restart]) runs force-stop then launch as one guarded unit (calling the *internal*
 * unguarded steps, not the public [forceStop]/[launch] entry points, so it does not deadlock against
 * its own guard key) and never reports [AppRestartResult.Success] unless both steps completed
 * without failure — if force-stop fails, launch is never attempted
 * ([AppRestartResult.ForceStopFailed]); if force-stop succeeds but launch fails, that is reported as
 * the distinct [AppRestartResult.LaunchFailedAfterForceStop] case, never coerced into
 * [AppRestartResult.Success] or a bare generic failure that could be misread as "nothing happened".
 */
class AppLifecycleUseCase(
    private val transport: AdbTransport,
    private val timeout: Duration = DEFAULT_APP_LIFECYCLE_TIMEOUT,
) {
    private val inFlightKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    suspend fun forceStop(serial: DeviceSerial, packageName: String): AppLifecycleResult =
        withGuard(serial, packageName, AppLifecycleResult.RejectedDuplicate) {
            forceStopResult(runForceStop(serial, packageName))
        }

    suspend fun launch(serial: DeviceSerial, packageName: String): AppLifecycleResult =
        withGuard(serial, packageName, AppLifecycleResult.RejectedDuplicate) {
            launchResult(runLaunch(serial, packageName), packageName)
        }

    suspend fun restart(serial: DeviceSerial, packageName: String): AppRestartResult =
        withGuard(serial, packageName, AppRestartResult.RejectedDuplicate) {
            val forceStopFailure = failureReason(forceStopResult(runForceStop(serial, packageName)))
            if (forceStopFailure != null) {
                return@withGuard AppRestartResult.ForceStopFailed(forceStopFailure)
            }
            val launchFailure = failureReason(launchResult(runLaunch(serial, packageName), packageName))
            if (launchFailure != null) {
                AppRestartResult.LaunchFailedAfterForceStop(launchFailure)
            } else {
                AppRestartResult.Success
            }
        }

    private suspend fun <T> withGuard(serial: DeviceSerial, packageName: String, rejected: T, block: suspend () -> T): T {
        val key = key(serial, packageName)
        if (!inFlightKeys.add(key)) return rejected
        try {
            return block()
        } finally {
            inFlightKeys.remove(key)
        }
    }

    private fun key(serial: DeviceSerial, packageName: String): String = "$serial|$packageName"

    private suspend fun runForceStop(serial: DeviceSerial, packageName: String): AdbTextResult = transport.executeText(
        AdbDeviceRequest(serial, AdbOperation.Shell(AppLifecycleCommands.forceStop(packageName)), timeout = timeout),
    )

    private suspend fun runLaunch(serial: DeviceSerial, packageName: String): AdbTextResult = transport.executeText(
        AdbDeviceRequest(serial, AdbOperation.Shell(AppLifecycleCommands.launch(packageName)), timeout = timeout),
    )

    private fun forceStopResult(result: AdbTextResult): AppLifecycleResult = when (val outcome = result.outcome) {
        is AdbOutcome.Completed ->
            if (outcome.exitCode == null || outcome.exitCode == 0) {
                AppLifecycleResult.Success
            } else {
                AppLifecycleResult.Failure("Command exited with code ${outcome.exitCode}")
            }
        is AdbOutcome.TimedOut -> AppLifecycleResult.Failure("Timed out")
        is AdbOutcome.Cancelled -> AppLifecycleResult.Failure("Cancelled")
        is AdbOutcome.TransportFailure -> AppLifecycleResult.Failure(outcome.reason)
        is AdbOutcome.Unsupported -> AppLifecycleResult.Failure(outcome.reason)
    }

    private fun launchResult(result: AdbTextResult, packageName: String): AppLifecycleResult = when (val outcome = result.outcome) {
        is AdbOutcome.Completed ->
            if (outcome.exitCode == null || outcome.exitCode == 0) {
                AppLifecycleResult.Success
            } else {
                AppLifecycleResult.Failure(launchFailureReason(outcome.exitCode, result.stdout, result.stderr, packageName))
            }
        is AdbOutcome.TimedOut -> AppLifecycleResult.Failure("Timed out")
        is AdbOutcome.Cancelled -> AppLifecycleResult.Failure("Cancelled")
        is AdbOutcome.TransportFailure -> AppLifecycleResult.Failure(outcome.reason)
        is AdbOutcome.Unsupported -> AppLifecycleResult.Failure(outcome.reason)
    }

    /**
     * A non-zero `monkey` exit whose combined stdout/stderr mentions "no activities found" is the
     * device reporting there is no launcher activity for [packageName] (ADR 0007's approved launch
     * strategy has no separate resolve-then-launch step, so this is where that failure mode
     * surfaces) — reported as its own distinct reason rather than a bare exit-code message.
     */
    private fun launchFailureReason(exitCode: Int?, stdout: String, stderr: String, packageName: String): String {
        val combined = (stdout + stderr).lowercase()
        return if (combined.contains(NO_LAUNCHER_MARKER)) {
            "No launcher activity found for $packageName"
        } else {
            "Command exited with code $exitCode"
        }
    }

    private fun failureReason(result: AppLifecycleResult): String? = (result as? AppLifecycleResult.Failure)?.reason
}
