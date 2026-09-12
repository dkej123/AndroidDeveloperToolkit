package dev.acme.adbtoolbox.domain.devicefacts

import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.ShellToken

/**
 * Builds the [AdbShellCommand] for each [DeviceFactId] (`design/IMPLEMENTATION.md` §4's `facts`
 * row). Every token is developer-authored command vocabulary ([ShellToken.Literal]) — no runtime
 * data is ever interpolated into a facts command, so none of these need [ShellToken.Value]
 * quoting. Kept separate from execution ([dev.acme.adbtoolbox.domain.adb.AdbTransport]) and parsing
 * ([DeviceFactsParsers]) per ADR 0001's "builder, executor call, parser" separation.
 */
object DeviceFactsCommands {

    fun commandFor(factId: DeviceFactId): AdbShellCommand = when (factId) {
        DeviceFactId.AndroidVersion -> AdbShellCommand.of(
            ShellToken.Literal("getprop"),
            ShellToken.Literal("ro.build.version.release"),
            ShellToken.Literal(";"),
            ShellToken.Literal("getprop"),
            ShellToken.Literal("ro.build.version.sdk"),
        )

        DeviceFactId.Resolution -> AdbShellCommand.of(ShellToken.Literal("wm"), ShellToken.Literal("size"))

        DeviceFactId.Density -> AdbShellCommand.of(ShellToken.Literal("wm"), ShellToken.Literal("density"))

        DeviceFactId.Battery -> AdbShellCommand.of(ShellToken.Literal("dumpsys"), ShellToken.Literal("battery"))

        DeviceFactId.Abi -> AdbShellCommand.of(ShellToken.Literal("getprop"), ShellToken.Literal("ro.product.cpu.abi"))

        DeviceFactId.Uptime -> AdbShellCommand.of(ShellToken.Literal("uptime"))
    }
}
