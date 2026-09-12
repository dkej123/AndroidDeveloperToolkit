package dev.acme.adbtoolbox.domain.deviceactions

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [DeviceActionCommands] builds the exact `adb shell` command lines task 016's Reboot/Wake basic
 * actions need, through [dev.acme.adbtoolbox.domain.adb.AdbShellCommand]'s typed tokens only —
 * both commands are fixed, developer-authored literals (no caller/runtime data), so there is
 * nothing to quote, but they still flow through the one shared command type per ADR 0005.
 */
class DeviceActionCommandsTest {

    @Test
    fun `reboot renders the plain reboot command`() {
        DeviceActionCommands.reboot().render() shouldBe "reboot"
    }

    @Test
    fun `wake renders the wake keyevent command`() {
        DeviceActionCommands.wake().render() shouldBe "input keyevent KEYCODE_WAKEUP"
    }
}
