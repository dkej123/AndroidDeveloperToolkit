package io.github.dkej123.devicecockpit.domain.display

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken

/**
 * The show-touches quick toggle (task 028, `design/IMPLEMENTATION.md` §4): reads and writes the
 * `system` `show_touches` setting. `settings get` on an unset key returns the literal string
 * `"null"`, which [parseRead] reports as [DisplaySettingRead.NotSet].
 */
object ShowTouchesCommand {

    private const val SETTING_NAME = "show_touches"

    fun readRequest(serial: DeviceSerial): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("settings"),
                ShellToken.Literal("get"),
                ShellToken.Literal("system"),
                ShellToken.Literal(SETTING_NAME),
            ),
        ),
    )

    fun writeRequest(serial: DeviceSerial, enabled: Boolean): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("settings"),
                ShellToken.Literal("put"),
                ShellToken.Literal("system"),
                ShellToken.Literal(SETTING_NAME),
                ShellToken.Literal(if (enabled) "1" else "0"),
            ),
        ),
    )

    fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> {
        if (!result.outcome.reachedDevice()) return DisplaySettingRead.TransportFailed(result.outcome)
        if (result.stderr.looksLikePermissionDenial()) return DisplaySettingRead.PermissionDenied(result.stderr)

        return when (result.stdout.trim()) {
            "1" -> DisplaySettingRead.Value(true)
            "0" -> DisplaySettingRead.Value(false)
            "null" -> DisplaySettingRead.NotSet
            else -> DisplaySettingRead.Malformed(raw = result.stdout, reason = "expected '1', '0', or 'null'")
        }
    }
}
