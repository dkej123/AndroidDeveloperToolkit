package io.github.dkej123.devicecockpit.application.currentapp

import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.foreground.ForegroundAppCommand
import io.github.dkej123.devicecockpit.domain.foreground.ForegroundState
import io.github.dkej123.devicecockpit.domain.foreground.HomeActivityCommand
import io.github.dkej123.devicecockpit.domain.foreground.PackageDetails
import io.github.dkej123.devicecockpit.domain.foreground.PackageDetailsCommand
import io.github.dkej123.devicecockpit.domain.foreground.PermissionReset
import io.github.dkej123.devicecockpit.domain.foreground.ProcessInfo
import io.github.dkej123.devicecockpit.domain.foreground.ProcessInfoCommand
import io.github.dkej123.devicecockpit.domain.packages.AppInfo
import io.github.dkej123.devicecockpit.domain.packages.AppInfoLookup
import kotlin.time.TimeSource

/** What Current app shows for an app in front (design §3a): the cheap part (package, PID) and the details. */
data class CurrentAppSnapshot(
    val foreground: ForegroundState,
    val details: PackageDetails? = null,
    val process: ProcessInfo? = null,
    /** Label and icon read from the device, so an app the Apps list has not loaded yet still shows its own. */
    val info: AppInfo? = null,
)

sealed interface PermissionResetResult {
    data class Done(val revoked: Int, val skippedFixed: Int) : PermissionResetResult

    data class Failed(val reason: String) : PermissionResetResult
}

/**
 * Reads the foreground app with its facts and resets its permissions (task 062). Package details
 * and the app's label and icon ([appInfo]) are re-read only when the foreground package changes
 * ([previous]); the launcher package is resolved once per device.
 */
class CurrentAppUseCase(
    private val transport: AdbTransport,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val appInfo: AppInfoLookup? = null,
) {
    private val launchers = mutableMapOf<DeviceSerial, String?>()

    /**
     * [refreshDetails] re-reads the package (version, runtime permissions) even when the same app stays in front: a
     * manual refresh or the Reset permissions menu must see permissions granted meanwhile in App details or on the phone.
     */
    /**
     * [stoppedApp]: the app we stopped (Reset permissions, Clear data) while the launcher is in front. It is still shown,
     * so a refresh re-reads its details too — its permission list changed with the stop.
     */
    suspend fun read(
        serial: DeviceSerial,
        previous: CurrentAppSnapshot? = null,
        refreshDetails: Boolean = false,
        stoppedApp: String? = null,
    ): CurrentAppSnapshot {
        val launcher = launchers.getOrPut(serial) { HomeActivityCommand.parse(transport.executeText(HomeActivityCommand.request(serial))) }
        val foreground = ForegroundAppCommand.parse(transport.executeText(ForegroundAppCommand.request(serial)), launcher)
        val app = when (foreground) {
            is ForegroundState.App -> foreground
            is ForegroundState.SystemUi -> foreground.behind
            else -> null
        } ?: return CurrentAppSnapshot(foreground, details = stoppedApp?.takeIf { refreshDetails }?.let { pkg ->
            PackageDetailsCommand.parse(transport.executeText(PackageDetailsCommand.request(serial, pkg)), pkg)
        })
        val previousApp = (previous?.foreground as? ForegroundState.App) ?: (previous?.foreground as? ForegroundState.SystemUi)?.behind
        val samePackage = previousApp?.packageName == app.packageName
        val details = if (samePackage && previous?.details != null && !refreshDetails) {
            previous.details
        } else {
            PackageDetailsCommand.parse(transport.executeText(PackageDetailsCommand.request(serial, app.packageName)), app.packageName)
        }
        val info = if (samePackage && previous?.info != null) {
            previous.info
        } else {
            runCatching { appInfo?.lookup(serial, app.packageName) }.getOrNull()
        }
        return CurrentAppSnapshot(foreground, details, process(serial, app.packageName, previous?.process), info)
    }

    /** PID changes on a restart; the elapsed time is re-read only for a new PID ([ProcessInfo.runningNow] adds the time since). */
    private suspend fun process(serial: DeviceSerial, packageName: String, previous: ProcessInfo?): ProcessInfo? {
        val pid = ProcessInfoCommand.parsePid(transport.executeText(ProcessInfoCommand.pidRequest(serial, packageName))) ?: return null
        if (previous?.pid == pid) return previous
        val elapsed = ProcessInfoCommand.parseElapsed(transport.executeText(ProcessInfoCommand.elapsedRequest(serial, pid)))
        return ProcessInfo(pid, elapsed, timeSource.markNow())
    }

    /** Grants one runtime permission (`pm grant`); `null` when done, else why not (user request, 2026-10-29). */
    suspend fun grantPermission(serial: DeviceSerial, packageName: String, permission: String, userId: Int = 0): String? {
        val result = transport.executeText(io.github.dkej123.devicecockpit.domain.appdata.PermissionCommands.grant(serial, packageName, permission, userId))
        // ddmlib merges stderr into stdout and reports no exit code: a failure shows up as an exception message.
        val output = (result.stderr + "\n" + result.stdout).trim()
        return when {
            result.outcome !is AdbOutcome.Completed -> "pm grant $permission failed"
            output.contains("Exception") || ((result.outcome as AdbOutcome.Completed).exitCode ?: 0) != 0 ->
                output.lineSequence().firstOrNull { it.isNotBlank() } ?: "pm grant $permission failed"
            else -> null
        }
    }

    /** Revokes every granted runtime permission, or only [permission] when given (task 067). */
    suspend fun resetPermissions(serial: DeviceSerial, packageName: String, userId: Int = 0, permission: String? = null): PermissionResetResult {
        val details = PackageDetailsCommand.parse(transport.executeText(PackageDetailsCommand.request(serial, packageName)), packageName)
            ?: return PermissionResetResult.Failed("Couldn't read the permissions of $packageName")
        val plan = PermissionReset.plan(serial, packageName, details, userId, only = permission)
        plan.steps.forEach { step ->
            val revoked = transport.executeText(step.revoke)
            if (revoked.outcome !is AdbOutcome.Completed || revoked.stderr.contains("Exception")) {
                return PermissionResetResult.Failed(revoked.stderr.trim().ifEmpty { "pm revoke ${step.permission} failed" })
            }
            // Unknown before API 31: the revoke alone still resets the grant.
            transport.executeText(step.clearFlags)
        }
        return PermissionResetResult.Done(revoked = plan.revoked.size, skippedFixed = plan.skippedFixed.size)
    }
}
