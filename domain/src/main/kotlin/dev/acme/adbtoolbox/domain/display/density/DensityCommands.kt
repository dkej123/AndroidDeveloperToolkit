package dev.acme.adbtoolbox.domain.display.density

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.ShellToken

/**
 * The `wm density` command factory (design/IMPLEMENTATION.md §4). [apply] takes an already
 * validated dpi (see [validateCustomDensity]) — construction itself still refuses a nonpositive
 * value as a last-resort guard, never trusting a caller to have validated.
 */
object DensityCommands {

    fun read(): AdbOperation.Shell = AdbOperation.Shell(
        AdbShellCommand.of(ShellToken.Literal("wm"), ShellToken.Literal("density")),
    )

    fun apply(dpi: Int): AdbOperation.Shell {
        require(dpi > 0) { "Density dpi must be positive, was $dpi" }
        return AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("wm"),
                ShellToken.Literal("density"),
                ShellToken.Literal(dpi.toString()),
            ),
        )
    }

    fun reset(): AdbOperation.Shell = AdbOperation.Shell(
        AdbShellCommand.of(ShellToken.Literal("wm"), ShellToken.Literal("density"), ShellToken.Literal("reset")),
    )
}
