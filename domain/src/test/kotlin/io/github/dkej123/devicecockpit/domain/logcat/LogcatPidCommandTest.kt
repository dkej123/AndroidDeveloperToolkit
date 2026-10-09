package io.github.dkej123.devicecockpit.domain.logcat

import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.ShellValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LogcatPidCommandTest {

    @Test
    fun `pidOf builds the pidof shell command with the package name as runtime data`() {
        val command = LogcatPidCommand.pidOf("com.acme.shop")

        command.tokens shouldBe listOf(
            ShellToken.Literal("pidof"),
            ShellToken.Value(ShellValue.of("com.acme.shop")),
        )
        command.render() shouldBe "pidof 'com.acme.shop'"
    }
}
