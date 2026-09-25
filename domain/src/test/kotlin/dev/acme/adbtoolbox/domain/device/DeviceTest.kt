package dev.acme.adbtoolbox.domain.device

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeviceTest {

    private val serial = DeviceSerial.of("emulator-5554")

    @Test
    fun `display name turns adb's underscore-joined model token back into the model name`() {
        // `adb devices -l` cannot print spaces inside a field, so "Pixel 8 Pro" arrives as
        // model:Pixel_8_Pro; users know the device by the spaced name (design/README.md §1).
        Device(serial, DeviceConnectionState.Online, model = "Android_SDK_built_for_x86").displayName shouldBe "Android SDK built for x86"
    }

    @Test
    fun `display name falls back to the serial when adb reports no model`() {
        Device(serial, DeviceConnectionState.Unauthorized).displayName shouldBe "emulator-5554"
    }
}
