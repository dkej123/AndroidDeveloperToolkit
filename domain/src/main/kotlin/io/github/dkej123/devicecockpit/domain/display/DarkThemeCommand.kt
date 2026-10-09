package io.github.dkej123.devicecockpit.domain.display

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken

/**
 * The dark-theme quick toggle (task 028, `design/IMPLEMENTATION.md` §4): reads and writes Android's
 * night-mode UI setting via `cmd uimode night`. That command surface varies by API level — some
 * devices answer "Night mode: yes"/"Night mode: no", older ones without the `uimode` service reply
 * with an unknown-command error, which [parseRead] reports as [DisplaySettingRead.NotSet] rather
 * than [DisplaySettingRead.Malformed], since it is a real, device-reported "not supported here."
 */
object DarkThemeCommand {

    fun readRequest(serial: DeviceSerial): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(ShellToken.Literal("cmd"), ShellToken.Literal("uimode"), ShellToken.Literal("night")),
        ),
    )

    fun writeRequest(serial: DeviceSerial, enabled: Boolean): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("cmd"),
                ShellToken.Literal("uimode"),
                ShellToken.Literal("night"),
                ShellToken.Literal(if (enabled) "yes" else "no"),
            ),
        ),
    )

    fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> {
        if (!result.outcome.reachedDevice()) return DisplaySettingRead.TransportFailed(result.outcome)
        if (result.stderr.looksLikePermissionDenial()) return DisplaySettingRead.PermissionDenied(result.stderr)

        val trimmed = result.stdout.trim()
        val lowerStdout = trimmed.lowercase()
        val lowerStderr = result.stderr.lowercase()
        return when {
            Regex("night mode:\\s*yes", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ->
                DisplaySettingRead.Value(true)
            Regex("night mode:\\s*no", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ->
                DisplaySettingRead.Value(false)
            lowerStdout.isEmpty() && (lowerStderr.contains("unknown command") || lowerStderr.contains("not found")) ->
                DisplaySettingRead.NotSet
            else -> DisplaySettingRead.Malformed(
                raw = result.stdout,
                reason = "expected 'Night mode: yes' or 'Night mode: no'",
            )
        }
    }
}
