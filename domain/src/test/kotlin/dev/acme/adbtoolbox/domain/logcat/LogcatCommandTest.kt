package dev.acme.adbtoolbox.domain.logcat

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LogcatCommandTest {

    @Test
    fun `request targets the exact USB or wireless serial and has caller-owned timeout`() {
        listOf("emulator-5554", "192.168.1.20:5555").forEach { rawSerial ->
            val serial = DeviceSerial.of(rawSerial)

            val request = LogcatCommand.request(serial)

            request.serial shouldBe serial
            request.timeout shouldBe null
            (request.operation as AdbOperation.Shell).command.render() shouldBe "logcat -v threadtime"
        }
    }
}
