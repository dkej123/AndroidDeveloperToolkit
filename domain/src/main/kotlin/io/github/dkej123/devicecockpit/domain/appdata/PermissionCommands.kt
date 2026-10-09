package io.github.dkej123.devicecockpit.domain.appdata

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.ShellValue

/** Typed command factory for per-user runtime-permission operations. */
object PermissionCommands {
    private val identifier = Regex("""[A-Za-z0-9_.]+""")

    fun grant(serial: DeviceSerial, packageName: String, permission: String, userId: Int) =
        request(serial, "grant", packageName, permission, userId)

    fun revoke(serial: DeviceSerial, packageName: String, permission: String, userId: Int) =
        request(serial, "revoke", packageName, permission, userId)

    fun listDangerous(serial: DeviceSerial) = AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(
        literal("pm"), literal("list"), literal("permissions"), literal("-g"), literal("-d"),
    )))

    fun parseDangerous(output: String): Set<String> = output.lineSequence().map(String::trim)
        .filter { it.startsWith("permission:") }.map { it.removePrefix("permission:") }
        .filter(identifier::matches).toSet()

    fun reset(serial: DeviceSerial, packageName: String, permission: String, userId: Int): List<AdbDeviceRequest> {
        validate(packageName, permission, userId)
        return listOf(
            revoke(serial, packageName, permission, userId),
            AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(
                literal("pm"), literal("clear-permission-flags"), literal("--user"), value(userId.toString()),
                value(packageName), value(permission), value("user-set"), value("user-fixed"),
            ))),
        )
    }

    private fun request(serial: DeviceSerial, operation: String, packageName: String, permission: String, userId: Int): AdbDeviceRequest {
        validate(packageName, permission, userId)
        return AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(
            literal("pm"), literal(operation), literal("--user"), value(userId.toString()), value(packageName), value(permission),
        )))
    }

    private fun validate(packageName: String, permission: String, userId: Int) {
        require(identifier.matches(packageName)) { "Invalid package name" }
        require(identifier.matches(permission)) { "Invalid permission name" }
        require(userId >= 0) { "Invalid Android user" }
    }

    private fun literal(text: String) = ShellToken.Literal(text)
    private fun value(text: String) = ShellToken.Value(ShellValue.of(text))
}
