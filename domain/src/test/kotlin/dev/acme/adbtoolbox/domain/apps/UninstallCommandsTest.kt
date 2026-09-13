package dev.acme.adbtoolbox.domain.apps

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UninstallCommandsTest {

    @Test
    fun `uninstall is a host adb operation and never a remote shell command`() {
        UninstallCommands.uninstall("com.acme.shop") shouldBe
            AdbOperation.Host(listOf("uninstall", "com.acme.shop"))
    }
}
