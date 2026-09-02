package dev.acme.adbtoolbox.domain.adb

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeviceSerialTest {

    @Test
    fun `rejects a blank serial`() {
        shouldThrow<IllegalArgumentException> { DeviceSerial.of("") }
    }

    @Test
    fun `rejects a whitespace-only serial`() {
        shouldThrow<IllegalArgumentException> { DeviceSerial.of("   ") }
    }

    @Test
    fun `preserves a USB-style serial exactly`() {
        DeviceSerial.of("R58N90ABCDE").value shouldBe "R58N90ABCDE"
    }

    @Test
    fun `preserves a Wi-Fi ip-colon-port serial exactly`() {
        DeviceSerial.of("192.168.1.42:5555").value shouldBe "192.168.1.42:5555"
    }

    @Test
    fun `toString returns the raw serial`() {
        DeviceSerial.of("192.168.1.42:5555").toString() shouldBe "192.168.1.42:5555"
    }
}
