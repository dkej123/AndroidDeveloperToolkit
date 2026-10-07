package dev.acme.adbtoolbox.domain.input

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InputCommandsTest {
    private val serial = DeviceSerial.of("emulator-5554")
    private fun AdbDeviceRequest.line() = (operation as AdbOperation.Shell).command.render()

    @Test
    fun `gestures and keys`() {
        InputCommands.tap(serial, 540, 1210).line() shouldBe "input tap 540 1210"
        InputCommands.swipe(serial, 10, 20, 30, 40, 300).line() shouldBe "input swipe 10 20 30 40 300"
        InputCommands.longPress(serial, 5, 6).line() shouldBe "input swipe 5 6 5 6 800"
        InputCommands.key(serial, SystemKey.Back).line() shouldBe "input keyevent 4"
        InputCommands.key(serial, SystemKey.of("notifications")!!).line() shouldBe "cmd statusbar expand-notifications"
        SystemKey.of("nope") shouldBe null
    }

    @Test
    fun `typed text keeps spaces as percent-s and is quoted`() {
        InputCommands.text(serial, "it's a test").line() shouldBe "input text 'it'\\''s%sa%stest'"
        InputCommands.canType("zażółć") shouldBe false
        InputCommands.canType("two\nlines") shouldBe false
        assertThrows<IllegalArgumentException> { InputCommands.text(serial, "ą") }
    }
}
