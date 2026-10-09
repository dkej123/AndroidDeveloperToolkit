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

/** What Current app shows for an app in front (design §3a): the cheap part (package, PID) and the details. */
data class CurrentAppSnapshot(
    val foreground: ForegroundState,
    val details: PackageDetails? = null,
    val process: ProcessInfo? = null,
)

sealed interface PermissionResetResult {
    data class Done(val revoked: Int, val skippedFixed: Int) : PermissionResetResult

    data class Failed(val reason: String) : PermissionResetResult
}

/**
 * Reads the foreground app with its facts and resets its permissions (task 062). Package details
 * are re-read only when the foreground package changes ([previous]); the launcher package is
 * resolved once per device.
 */
class CurrentAppUseCase(private val transport: AdbTransport) {
    private val launchers = mutableMapOf<DeviceSerial, String?>()

    suspend fun read(serial: DeviceSerial, previous: CurrentAppSnapshot? = null): CurrentAppSnapshot {
        val launcher = launchers.getOrPut(serial) { HomeActivityCommand.parse(transport.executeText(HomeActivityCommand.request(serial))) }
        val foreground = ForegroundAppCommand.parse(transport.executeText(ForegroundAppCommand.request(serial)), launcher)
        val app = when (foreground) {
            is ForegroundState.App -> foreground
            is ForegroundState.SystemUi -> foreground.behind
            else -> null
        } ?: return CurrentAppSnapshot(foreground)
        val previousApp = (previous?.foreground as? ForegroundState.App) ?: (previous?.foreground as? ForegroundState.SystemUi)?.behind
        val details = if (previousApp?.packageName == app.packageName && previous?.details != null) {
            previous.details
        } else {
            PackageDetailsCommand.parse(transport.executeText(PackageDetailsCommand.request(serial, app.packageName)), app.packageName)
        }
        return CurrentAppSnapshot(foreground, details, process(serial, app.packageName, previous?.process))
    }

    /** PID changes on a restart; the elapsed time is re-read only for a new PID (callers add the time since). */
    private suspend fun process(serial: DeviceSerial, packageName: String, previous: ProcessInfo?): ProcessInfo? {
        val pid = ProcessInfoCommand.parsePid(transport.executeText(ProcessInfoCommand.pidRequest(serial, packageName))) ?: return null
        if (previous?.pid == pid) return previous
        return ProcessInfo(pid, ProcessInfoCommand.parseElapsed(transport.executeText(ProcessInfoCommand.elapsedRequest(serial, pid))))
    }

    suspend fun resetPermissions(serial: DeviceSerial, packageName: String, userId: Int = 0): PermissionResetResult {
        val details = PackageDetailsCommand.parse(transport.executeText(PackageDetailsCommand.request(serial, packageName)), packageName)
            ?: return PermissionResetResult.Failed("Couldn't read the permissions of $packageName")
        val plan = PermissionReset.plan(serial, packageName, details, userId)
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
