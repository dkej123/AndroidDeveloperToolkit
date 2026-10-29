package io.github.dkej123.devicecockpit.domain.apps

import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.ShellValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Covers task 023's exact-command-value TDD requirement for Force-stop and Launch, pinned by
 * `design/IMPLEMENTATION.md` §4: `am force-stop <pkg>` and
 * the first LAUNCHER activity (monkey only as the fallback).
 */
class AppLifecycleCommandsTest {

    @Test
    fun `force-stop renders the exact design-pinned command line for the package`() {
        AppLifecycleCommands.forceStop("com.acme.shop").render() shouldBe "am force-stop 'com.acme.shop'"
    }

    /**
     * `monkey -p <pkg> -c LAUNCHER 1` picks a random LAUNCHER activity: an app that also ships LeakCanary's or a
     * dev-tools launcher opened those at random (user report, 2026-10-29). Launch starts the first LAUNCHER activity in
     * manifest order — what Android's getLaunchIntentForPackage picks — and falls back to monkey only when the query
     * finds none, so a missing launcher still reports "No activities found". Verified on a Galaxy S23.
     */
    @Test
    fun `launch starts the first launcher activity and falls back to monkey`() {
        AppLifecycleCommands.launch("com.acme.shop").render() shouldBe
            "c=\$(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 'com.acme.shop'" +
            " | grep -m1 / | tr -d ' ') && [ -n \"\$c\" ]" +
            " && am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n \"\$c\"" +
            " || monkey -p 'com.acme.shop' -c android.intent.category.LAUNCHER 1"
    }

    @Test
    fun `force-stop wraps the package name as a runtime Value token, never a literal`() {
        val command = AppLifecycleCommands.forceStop("com.acme.shop")

        command.tokens.last() shouldBe ShellToken.Value(ShellValue.of("com.acme.shop"))
    }

    @Test
    fun `launch wraps the package name as a runtime Value token, never a literal`() {
        val command = AppLifecycleCommands.launch("com.acme.sho'p")
        command.tokens.filterIsInstance<ShellToken.Value>() shouldBe List(2) { ShellToken.Value(ShellValue.of("com.acme.sho'p")) }
        command.render().contains("'com.acme.sho'\\''p'") shouldBe true
    }

    @Test
    fun `an embedded single quote in the package name is safely escaped, never breaking out of quoting`() {
        AppLifecycleCommands.forceStop("com.acme.sho'p").render() shouldBe "am force-stop 'com.acme.sho'\\''p'"
    }
}
