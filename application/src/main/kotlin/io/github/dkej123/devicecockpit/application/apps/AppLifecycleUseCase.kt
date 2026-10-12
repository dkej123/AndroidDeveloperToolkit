package io.github.dkej123.devicecockpit.application.apps

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.apps.AppLifecycleCommands
import io.github.dkej123.devicecockpit.domain.apps.AppLifecycleResult
import io.github.dkej123.devicecockpit.domain.apps.AppProcessDeathResult
import io.github.dkej123.devicecockpit.domain.foreground.ProcessInfoCommand
import kotlinx.coroutines.delay
import io.github.dkej123.devicecockpit.domain.apps.AppRestartResult
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Bounded request/response default (ADR 0005); force-stop/launch are one-shot calls, not streams. */
val DEFAULT_APP_LIFECYCLE_TIMEOUT: Duration = 10.seconds

// How long simulateProcessDeath waits for `am kill` to take the process down.
private const val KILL_CHECKS = 5
private val KILL_CHECK_INTERVAL = 300.milliseconds

/** A `monkey` launch failure's stdout/stderr marker for "no launchable activity" (case-insensitive). */
private const val NO_LAUNCHER_MARKER = "no activities found"

/**
 * Runs task 023's Force-stop, Launch, and Restart app-lifecycle actions through the [AdbTransport]
 * gateway (`design/README.md` §4's Apps-view action footer), for the exact `(serial, packageName)`
 * pair passed to each call — never an implicit "current selection" read internally — mirroring
 * [io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsUseCase]'s parameter discipline.
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

    /**
     * Simulates the system killing [packageName] while it is in the background (process death): sends
     * it home, waits [saveStateDelay] for `onSaveInstanceState`/`onStop` to run, kills it with
     * `am kill`, waits until the process is gone, then launches it again — Android brings back its
     * task and recreates the activity from saved state, which is what this tests. Unlike [restart],
     * nothing is cleared. Verified on a Galaxy S23 (Android 16): `wm_restart_activity` for the same
     * ActivityRecord in a new process.
     */
    suspend fun simulateProcessDeath(
        serial: DeviceSerial,
        packageName: String,
        saveStateDelay: Duration = 1.seconds,
    ): AppProcessDeathResult = withGuard(serial, packageName, AppProcessDeathResult.RejectedDuplicate) {
        if (pid(serial, packageName) == null) return@withGuard AppProcessDeathResult.NotRunning
        commandFailure(shell(serial, AppLifecycleCommands.home()))?.let { return@withGuard AppProcessDeathResult.Failure(it) }
        delay(saveStateDelay)
        commandFailure(shell(serial, AppLifecycleCommands.killBackground(packageName)))?.let { return@withGuard AppProcessDeathResult.Failure(it) }
        var gone = false
        for (check in 0 until KILL_CHECKS) {
            if (check > 0) delay(KILL_CHECK_INTERVAL)
            if (pid(serial, packageName) == null) {
                gone = true
                break
            }
        }
        if (!gone) return@withGuard AppProcessDeathResult.StillAlive
        when (val launched = launchResult(runLaunch(serial, packageName), packageName)) {
            is AppLifecycleResult.Failure -> AppProcessDeathResult.RelaunchFailed(launched.reason)
            else -> AppProcessDeathResult.Success
        }
    }

    private suspend fun shell(serial: DeviceSerial, command: io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand): AdbTextResult =
        transport.executeText(AdbDeviceRequest(serial, AdbOperation.Shell(command), timeout = timeout))

    private suspend fun pid(serial: DeviceSerial, packageName: String): Int? =
        ProcessInfoCommand.parsePid(transport.executeText(ProcessInfoCommand.pidRequest(serial, packageName)))

    private fun commandFailure(result: AdbTextResult): String? = failureReason(forceStopResult(result))

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
