package dev.acme.adbtoolbox.domain.input

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue

/** A system key an agent can press, with the shell line that presses it. */
enum class SystemKey(val id: String, val command: List<String>) {
    Back("back", listOf("input", "keyevent", "4")),
    Home("home", listOf("input", "keyevent", "3")),
    Recents("recents", listOf("input", "keyevent", "187")),
    Enter("enter", listOf("input", "keyevent", "66")),
    Delete("delete", listOf("input", "keyevent", "67")),
    Tab("tab", listOf("input", "keyevent", "61")),
    Escape("escape", listOf("input", "keyevent", "111")),
    Up("up", listOf("input", "keyevent", "19")),
    Down("down", listOf("input", "keyevent", "20")),
    Left("left", listOf("input", "keyevent", "21")),
    Right("right", listOf("input", "keyevent", "22")),
    VolumeUp("volume_up", listOf("input", "keyevent", "24")),
    VolumeDown("volume_down", listOf("input", "keyevent", "25")),
    Power("power", listOf("input", "keyevent", "26")),
    Wakeup("wakeup", listOf("input", "keyevent", "224")),
    Notifications("notifications", listOf("cmd", "statusbar", "expand-notifications")),
    QuickSettings("quick_settings", listOf("cmd", "statusbar", "expand-settings")),
    ;

    companion object {
        fun of(id: String): SystemKey? = entries.firstOrNull { it.id == id }
    }
}

/**
 * `input` gestures and typing in device pixels (callers convert from dp).
 *
 * Ported from Oh My Android, MIT — `Sources/MCP/Tools/InputTools.swift`.
 */
object InputCommands {
    fun tap(serial: DeviceSerial, x: Int, y: Int) = shell(serial, "input", "tap", x.toString(), y.toString())

    fun longPress(serial: DeviceSerial, x: Int, y: Int, durationMs: Int = 800) = swipe(serial, x, y, x, y, durationMs)

    fun swipe(serial: DeviceSerial, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        shell(serial, "input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())

    fun key(serial: DeviceSerial, key: SystemKey) = shell(serial, *key.command.toTypedArray())

    /** True when `input text` can type [text]: printable ASCII on one line (an adb limit). */
    fun canType(text: String): Boolean = text.isNotEmpty() && text.all { it.code in 0x20..0x7E }

    /** `input text` reads `%s` as a space; the text itself is shell-quoted. */
    fun text(serial: DeviceSerial, text: String): AdbDeviceRequest {
        require(canType(text)) { "input text types printable ASCII only" }
        return AdbDeviceRequest(
            serial,
            AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("input"), ShellToken.Literal("text"), ShellToken.Value(ShellValue.of(text.replace(" ", "%s"))))),
        )
    }

    private fun shell(serial: DeviceSerial, vararg literals: String) =
        AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(*literals.map { ShellToken.Literal(it) }.toTypedArray())))
}
