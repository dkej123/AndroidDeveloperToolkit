package dev.acme.adbtoolbox.domain.display

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken

/**
 * Reads and writes one [AnimationScaleSetting] via `settings get/put global <name>`
 * (`design/IMPLEMENTATION.md` §4). The "Animations off" toggle issues this three times — once per
 * [AnimationScaleSetting] — never as a single combined command, since the three settings are
 * independent on the device.
 */
object AnimationScaleCommand {

    fun readRequest(serial: DeviceSerial, setting: AnimationScaleSetting): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("settings"),
                ShellToken.Literal("get"),
                ShellToken.Literal("global"),
                ShellToken.Literal(setting.settingName),
            ),
        ),
    )

    fun writeRequest(serial: DeviceSerial, setting: AnimationScaleSetting, enabled: Boolean): AdbDeviceRequest =
        AdbDeviceRequest(
            serial = serial,
            operation = AdbOperation.Shell(
                AdbShellCommand.of(
                    ShellToken.Literal("settings"),
                    ShellToken.Literal("put"),
                    ShellToken.Literal("global"),
                    ShellToken.Literal(setting.settingName),
                    ShellToken.Literal(if (enabled) "1" else "0"),
                ),
            ),
        )

    fun parseRead(result: AdbTextResult): DisplaySettingRead<Float> {
        if (!result.outcome.reachedDevice()) return DisplaySettingRead.TransportFailed(result.outcome)
        if (result.stderr.looksLikePermissionDenial()) return DisplaySettingRead.PermissionDenied(result.stderr)

        val trimmed = result.stdout.trim()
        if (trimmed == "null") return DisplaySettingRead.NotSet
        val value = trimmed.toFloatOrNull()
            ?: return DisplaySettingRead.Malformed(
                raw = result.stdout,
                reason = "expected a decimal scale value or 'null'",
            )
        return DisplaySettingRead.Value(value)
    }
}
