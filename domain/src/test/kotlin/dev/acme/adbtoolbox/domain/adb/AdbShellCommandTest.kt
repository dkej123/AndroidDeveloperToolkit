package dev.acme.adbtoolbox.domain.adb

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AdbShellCommandTest {

    @Test
    fun `renders literal tokens unescaped`() {
        val command = AdbShellCommand.of(
            ShellToken.Literal("pm"),
            ShellToken.Literal("list"),
            ShellToken.Literal("packages"),
        )

        command.render() shouldBe "pm list packages"
    }

    @Test
    fun `renders a value token single-quoted`() {
        val command = AdbShellCommand.of(
            ShellToken.Literal("pm"),
            ShellToken.Literal("path"),
            ShellToken.Value(ShellValue.of("com.example.app")),
        )

        command.render() shouldBe "pm path 'com.example.app'"
    }

    @Test
    fun `escapes an embedded single quote so it cannot break out of the quoted value`() {
        val command = AdbShellCommand.of(
            ShellToken.Literal("echo"),
            ShellToken.Value(ShellValue.of("foo'; rm -rf /sdcard; echo 'bar")),
        )

        // Single-quote wrapping with embedded-quote escaping, per ADR 0005: each embedded `'`
        // becomes `'\''` — closes the quoted string, escapes a literal quote, reopens quoting.
        command.render() shouldBe "echo 'foo'\\''; rm -rf /sdcard; echo '\\''bar'"
    }

    @Test
    fun `a value token containing shell metacharacters is never interpreted as a separate token`() {
        val command = AdbShellCommand.of(
            ShellToken.Literal("echo"),
            ShellToken.Value(ShellValue.of("a b; touch /sdcard/pwned")),
        )

        command.render() shouldBe "echo 'a b; touch /sdcard/pwned'"
    }

    @Test
    fun `requires at least one token`() {
        shouldThrow<IllegalArgumentException> { AdbShellCommand(emptyList()) }
    }
}
