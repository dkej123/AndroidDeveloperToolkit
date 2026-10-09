package io.github.dkej123.devicecockpit.domain.foreground

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.ShellValue

/** A runtime permission of a package for one user, as `dumpsys package` lists it. */
data class RuntimePermission(val name: String, val granted: Boolean, val flags: Set<String>) {
    /** Fixed by the system or a device policy: `pm revoke` would fail, so a reset leaves it alone. */
    val fixed: Boolean get() = "SYSTEM_FIXED" in flags || "POLICY_FIXED" in flags
}

/** The facts Current app shows (design §3a) plus what Reset permissions needs. */
data class PackageDetails(
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val debuggable: Boolean,
    val system: Boolean,
    val runtimePermissions: List<RuntimePermission>,
)

/** `dumpsys package <pkg>`, parsed defensively for the first `Package [<pkg>]` block. */
object PackageDetailsCommand {
    fun request(serial: DeviceSerial, packageName: String) = AdbDeviceRequest(
        serial,
        AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("dumpsys"), ShellToken.Literal("package"), ShellToken.Value(ShellValue.of(packageName)))),
    )

    private val PERMISSION = Regex("""^\s*([\w.]+): granted=(true|false)(?:, flags=\[\s*([^\]]*)])?""")

    fun parse(result: AdbTextResult, packageName: String): PackageDetails? {
        if (result.outcome !is AdbOutcome.Completed) return null
        val lines = result.stdout.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("Package [$packageName]") }
        if (start < 0) return null
        val block = lines.drop(start + 1).takeWhile { !it.trim().startsWith("Package [") && !(it.isNotEmpty() && !it[0].isWhitespace()) }
        fun field(name: String): String? = block.firstNotNullOfOrNull { line ->
            Regex("""(?:^|\s)$name=(\S+)""").find(line)?.groupValues?.get(1)
        }
        val flags = block.firstOrNull { it.trim().startsWith("flags=[") || it.trim().startsWith("pkgFlags=[") }
            ?.substringAfter('[')?.substringBefore(']')?.trim()?.split(Regex("\\s+"))?.toSet().orEmpty()
        // A package sharing a user id (e.g. android.uid.system) has its permissions under "Shared users:".
        val afterStart = lines.drop(start + 1)
        val permissionsStart = afterStart.indexOfFirst { it.trim() == "runtime permissions:" }
        val permissions = if (permissionsStart < 0) emptyList() else afterStart.drop(permissionsStart + 1)
            .takeWhile { PERMISSION.containsMatchIn(it) }
            .mapNotNull { line ->
                PERMISSION.find(line)?.let { m ->
                    RuntimePermission(m.groupValues[1], m.groupValues[2] == "true", m.groupValues[3].split('|').map(String::trim).filter(String::isNotEmpty).toSet())
                }
            }
        return PackageDetails(
            versionName = field("versionName"),
            versionCode = field("versionCode")?.toLongOrNull(),
            minSdk = field("minSdk")?.toIntOrNull(),
            targetSdk = field("targetSdk")?.toIntOrNull(),
            debuggable = "DEBUGGABLE" in flags,
            system = "SYSTEM" in flags,
            runtimePermissions = permissions,
        )
    }
}

/** Revoking one permission, then clearing its "Don't ask again" flags. */
data class PermissionResetStep(val permission: String, val revoke: AdbDeviceRequest, val clearFlags: AdbDeviceRequest)

/** The steps of a Reset permissions (design §3a) and what they leave out. */
data class PermissionResetPlan(val steps: List<PermissionResetStep>, val skippedFixed: List<String>) {
    val revoked: List<String> get() = steps.map { it.permission }
}

/**
 * Reset permissions for one app: `pm revoke` every granted, non-fixed runtime permission and clear
 * its user-set / user-fixed flags ("Don't ask again"; API 31+, an unknown command on older releases
 * is harmless). Never `pm reset-permissions`, which resets every app on the device.
 */
object PermissionReset {
    /** Every granted runtime permission, or only [only] when given. */
    fun plan(serial: DeviceSerial, packageName: String, details: PackageDetails, userId: Int = 0, only: String? = null): PermissionResetPlan {
        val granted = details.runtimePermissions.filter { it.granted && (only == null || it.name == only) }
        val (fixed, revocable) = granted.partition { it.fixed }
        val steps = revocable.map { permission ->
            PermissionResetStep(
                permission = permission.name,
                revoke = pm(serial, userId, "revoke", packageName, permission.name),
                clearFlags = pm(serial, userId, "clear-permission-flags", packageName, permission.name, "user-set", "user-fixed"),
            )
        }
        return PermissionResetPlan(steps, fixed.map { it.name })
    }

    private fun pm(serial: DeviceSerial, userId: Int, verb: String, packageName: String, permission: String, vararg tail: String) =
        AdbDeviceRequest(
            serial,
            AdbOperation.Shell(
                AdbShellCommand.of(
                    ShellToken.Literal("pm"), ShellToken.Literal(verb), ShellToken.Literal("--user"), ShellToken.Literal(userId.toString()),
                    ShellToken.Value(ShellValue.of(packageName)), ShellToken.Value(ShellValue.of(permission)),
                    *tail.map { ShellToken.Literal(it) }.toTypedArray(),
                ),
            ),
        )
}
