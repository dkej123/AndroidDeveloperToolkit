package dev.acme.adbtoolbox.domain.network

import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue

/**
 * Builds the global-proxy shell commands `design/IMPLEMENTATION.md` §4 specifies, from typed
 * [ShellToken]s only (ADR 0005) — the only way to reach [enable] is with an already-validated
 * [ProxyEndpoint], so an invalid host/port can never be rendered into a command.
 */
object ProxyCommands {

    private const val RESET_VALUE = ":0"

    fun enable(endpoint: ProxyEndpoint): AdbShellCommand = settingsPut(ShellValue.of(endpoint.render()))

    fun reset(): AdbShellCommand = settingsPut(ShellValue.of(RESET_VALUE))

    fun read(): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("settings"),
        ShellToken.Literal("get"),
        ShellToken.Literal("global"),
        ShellToken.Literal("http_proxy"),
    )

    private fun settingsPut(value: ShellValue): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("settings"),
        ShellToken.Literal("put"),
        ShellToken.Literal("global"),
        ShellToken.Literal("http_proxy"),
        ShellToken.Value(value),
    )
}
