package dev.acme.adbtoolbox.domain.apps

import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Covers task 023's exact-command-value TDD requirement for Force-stop and Launch, pinned by
 * `design/IMPLEMENTATION.md` §4: `am force-stop <pkg>` and
 * `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`.
 */
class AppLifecycleCommandsTest {

    @Test
    fun `force-stop renders the exact design-pinned command line for the package`() {
        AppLifecycleCommands.forceStop("com.acme.shop").render() shouldBe "am force-stop 'com.acme.shop'"
    }

    @Test
    fun `launch renders the exact design-pinned monkey launcher command line for the package`() {
        AppLifecycleCommands.launch("com.acme.shop").render() shouldBe
            "monkey -p 'com.acme.shop' -c android.intent.category.LAUNCHER 1"
    }

    @Test
    fun `force-stop wraps the package name as a runtime Value token, never a literal`() {
        val command = AppLifecycleCommands.forceStop("com.acme.shop")

        command.tokens.last() shouldBe ShellToken.Value(ShellValue.of("com.acme.shop"))
    }

    @Test
    fun `launch wraps the package name as a runtime Value token, never a literal`() {
        val command = AppLifecycleCommands.launch("com.acme.shop")

        command shouldBe AdbShellCommand.of(
            ShellToken.Literal("monkey"),
            ShellToken.Literal("-p"),
            ShellToken.Value(ShellValue.of("com.acme.shop")),
            ShellToken.Literal("-c"),
            ShellToken.Literal("android.intent.category.LAUNCHER"),
            ShellToken.Literal("1"),
        )
    }

    @Test
    fun `an embedded single quote in the package name is safely escaped, never breaking out of quoting`() {
        AppLifecycleCommands.forceStop("com.acme.sho'p").render() shouldBe "am force-stop 'com.acme.sho'\\''p'"
    }
}
