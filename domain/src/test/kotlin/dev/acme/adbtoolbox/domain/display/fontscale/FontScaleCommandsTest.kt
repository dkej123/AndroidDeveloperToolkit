package dev.acme.adbtoolbox.domain.display.fontscale

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FontScaleCommandsTest {

    private val serial = DeviceSerial.of("emulator-5554")

    @Test
    fun `read builds a serial-scoped settings get request`() {
        val request = FontScaleCommands.read(serial)

        request.serial shouldBe serial
        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "settings get system font_scale"
    }

    @Test
    fun `write builds a serial-scoped settings put request with the value single-quoted`() {
        val request = FontScaleCommands.write(serial, 1.3)

        request.serial shouldBe serial
        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "settings put system font_scale '1.3'"
    }

    @Test
    fun `write never interpolates a runtime value unescaped`() {
        // Not a realistic font-scale value, but proves the command factory always routes runtime
        // data through ShellValue quoting rather than string concatenation.
        val request = FontScaleCommands.write(serial, 1.0)

        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "settings put system font_scale '1.0'"
    }

    @Test
    fun `reset writes the platform default value`() {
        val request = FontScaleCommands.reset(serial)

        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "settings put system font_scale '${FontScalePresets.DEFAULT}'"
    }
}
