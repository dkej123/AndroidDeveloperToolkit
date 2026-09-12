package dev.acme.adbtoolbox.domain.deviceactions

import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.ShellToken

/**
 * Builds the fixed `adb shell` command lines for task 016's Reboot and Wake basic actions
 * (`design/README.md` §3's Device-view action row), from typed [ShellToken]s only (ADR 0005) —
 * both commands are developer-authored literals with no caller/runtime data to quote, but still
 * flow through the one shared [AdbShellCommand] type like every other feature's command factory.
 */
object DeviceActionCommands {

    fun reboot(): AdbShellCommand = AdbShellCommand.of(ShellToken.Literal("reboot"))

    fun wake(): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("input"),
        ShellToken.Literal("keyevent"),
        ShellToken.Literal("KEYCODE_WAKEUP"),
    )
}
