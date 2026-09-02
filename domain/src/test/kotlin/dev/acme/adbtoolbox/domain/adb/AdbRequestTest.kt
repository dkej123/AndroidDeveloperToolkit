package dev.acme.adbtoolbox.domain.adb

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AdbRequestTest {

    @Test
    fun `device-scoped request cannot be constructed without a serial value`() {
        // DeviceSerial.of is the only constructor path, and it rejects blanks (see DeviceSerialTest) —
        // AdbDeviceRequest has no secondary constructor that bypasses it, so the type itself enforces
        // "every device operation is serial-scoped."
        val serial = DeviceSerial.of("emulator-5554")
        val request = AdbDeviceRequest(
            serial = serial,
            operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("getprop"))),
        )

        request.serial shouldBe serial
    }

    @Test
    fun `device-scoped request preserves a Wi-Fi serial exactly through construction`() {
        val request = AdbDeviceRequest(
            serial = DeviceSerial.of("10.0.0.5:5555"),
            operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("getprop"))),
        )

        request.serial.value shouldBe "10.0.0.5:5555"
    }

    @Test
    fun `server-scoped request carries no serial`() {
        val request = AdbServerRequest(arguments = listOf("devices", "-l"))

        request.arguments shouldBe listOf("devices", "-l")
    }
}
