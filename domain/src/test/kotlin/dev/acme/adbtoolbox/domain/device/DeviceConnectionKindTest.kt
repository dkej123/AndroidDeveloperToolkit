package dev.acme.adbtoolbox.domain.device

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeviceConnectionKindTest {

    @Test
    fun `a host-port serial is a Wi-Fi device`() {
        DeviceConnectionKind.of("10.0.4.91:5555") shouldBe DeviceConnectionKind.Wifi
    }

    @Test
    fun `an emulator console serial is an emulator, not USB`() {
        DeviceConnectionKind.of("emulator-5554") shouldBe DeviceConnectionKind.Emulator
    }

    @Test
    fun `any other serial is a USB device`() {
        DeviceConnectionKind.of("49060DLAQ002W7") shouldBe DeviceConnectionKind.Usb
        DeviceConnectionKind.of("emulator-abc") shouldBe DeviceConnectionKind.Usb
    }
}
