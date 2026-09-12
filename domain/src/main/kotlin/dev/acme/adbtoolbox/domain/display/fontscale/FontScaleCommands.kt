package dev.acme.adbtoolbox.domain.display.fontscale

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue

/**
 * Feature-local command factory (task 003's pattern) for `settings get/put system font_scale`
 * (`design/IMPLEMENTATION.md` §4). Every value is routed through [ShellValue]/[ShellToken.Value] so
 * a runtime font-scale value is never string-concatenated into the shell command line.
 */
object FontScaleCommands {

    fun read(serial: DeviceSerial): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("settings"),
                ShellToken.Literal("get"),
                ShellToken.Literal("system"),
                ShellToken.Literal("font_scale"),
            ),
        ),
    )

    fun write(serial: DeviceSerial, value: Double): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("settings"),
                ShellToken.Literal("put"),
                ShellToken.Literal("system"),
                ShellToken.Literal("font_scale"),
                ShellToken.Value(ShellValue.of(formatFontScale(value))),
            ),
        ),
    )

    fun reset(serial: DeviceSerial): AdbDeviceRequest = write(serial, FontScalePresets.DEFAULT)
}
