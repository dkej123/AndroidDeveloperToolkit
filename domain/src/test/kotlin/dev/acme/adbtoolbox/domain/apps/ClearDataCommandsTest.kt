package dev.acme.adbtoolbox.domain.apps

import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Covers task 024's TDD plan for the `pm clear` command factory: the exact shell command line
 * (`design/IMPLEMENTATION.md` §4), built from typed [ShellToken]s only (ADR 0005) with the package
 * name always wrapped in [ShellToken.Value]/[ShellValue.of] — never string-interpolated — matching
 * [dev.acme.adbtoolbox.domain.apps.AppLifecycleCommands]'s established shape for the same `<pkg>`
 * argument.
 */
class ClearDataCommandsTest {

    @Test
    fun `clear builds the pm clear shell command from typed tokens with the package as a value`() {
        val command = ClearDataCommands.clear("com.acme.shop")

        command shouldBe AdbShellCommand.of(
            ShellToken.Literal("pm"),
            ShellToken.Literal("clear"),
            ShellToken.Value(ShellValue.of("com.acme.shop")),
        )
    }

    @Test
    fun `clear never inlines the package name as a literal token`() {
        val command = ClearDataCommands.clear("com.acme.shop")

        command.tokens.filterIsInstance<ShellToken.Literal>().none { it.text == "com.acme.shop" } shouldBe true
    }
}
