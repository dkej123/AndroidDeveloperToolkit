package dev.acme.adbtoolbox.domain.device

import dev.acme.adbtoolbox.domain.adb.AdbParseResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class DeviceListParserTest {

    @Test
    fun `parses a single online USB device with full optional fields`() {
        val output = """
            List of devices attached
            R58N90ABCDE            device usb:1-1 product:redfin model:Pixel_5 device:redfin transport_id:3

        """.trimIndent()

        val results = DeviceListParser.parse(output)

        results shouldBe listOf(
            AdbParseResult.Parsed(
                Device(
                    serial = DeviceSerial.of("R58N90ABCDE"),
                    state = DeviceConnectionState.Online,
                    product = "redfin",
                    model = "Pixel_5",
                    device = "redfin",
                    transportId = "3",
                ),
            ),
        )
    }

    @Test
    fun `parses a wireless ip colon port serial`() {
        val output = "List of devices attached\n192.168.1.42:5555     device product:redfin model:Pixel_5 device:redfin transport_id:5\n"

        val results = DeviceListParser.parse(output)

        results shouldBe listOf(
            AdbParseResult.Parsed(
                Device(
                    serial = DeviceSerial.of("192.168.1.42:5555"),
                    state = DeviceConnectionState.Online,
                    product = "redfin",
                    model = "Pixel_5",
                    device = "redfin",
                    transportId = "5",
                ),
            ),
        )

        (results[0] as AdbParseResult.Parsed).value.connectionKind shouldBe DeviceConnectionKind.Wifi
    }

    @Test
    fun `parses multiple devices in one output`() {
        val output = """
            List of devices attached
            R58N90ABCDE            device usb:1-1 product:redfin model:Pixel_5 device:redfin transport_id:3
            192.168.1.42:5555      device product:redfin model:Pixel_5 device:redfin transport_id:5
            emulator-5554          offline transport_id:1

        """.trimIndent()

        val results = DeviceListParser.parse(output)

        results.filterIsInstance<AdbParseResult.Parsed<Device>>().map { it.value.serial } shouldBe listOf(
            DeviceSerial.of("R58N90ABCDE"),
            DeviceSerial.of("192.168.1.42:5555"),
            DeviceSerial.of("emulator-5554"),
        )
    }

    @Test
    fun `skips adb daemon startup chatter lines`() {
        val output = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            R58N90ABCDE            device usb:1-1 product:redfin model:Pixel_5 device:redfin transport_id:3

        """.trimIndent()

        val results = DeviceListParser.parse(output)

        results.size shouldBe 1
        results[0].shouldBeInstanceOf<AdbParseResult.Parsed<Device>>()
    }

    @Test
    fun `handles CRLF line endings`() {
        val output = "List of devices attached\r\nR58N90ABCDE            device usb:1-1 product:redfin model:Pixel_5 device:redfin transport_id:3\r\n"

        val results = DeviceListParser.parse(output)

        results shouldBe listOf(
            AdbParseResult.Parsed(
                Device(
                    serial = DeviceSerial.of("R58N90ABCDE"),
                    state = DeviceConnectionState.Online,
                    product = "redfin",
                    model = "Pixel_5",
                    device = "redfin",
                    transportId = "3",
                ),
            ),
        )
    }

    @Test
    fun `handles missing optional fields`() {
        val output = "List of devices attached\nR58N90ABCDE            device\n"

        val results = DeviceListParser.parse(output)

        results shouldBe listOf(
            AdbParseResult.Parsed(
                Device(
                    serial = DeviceSerial.of("R58N90ABCDE"),
                    state = DeviceConnectionState.Online,
                    product = null,
                    model = null,
                    device = null,
                    transportId = null,
                ),
            ),
        )
    }

    @Test
    fun `duplicate serials in raw output are all parsed, deduped by parseDevices with last occurrence winning`() {
        val output = """
            List of devices attached
            R58N90ABCDE            offline
            R58N90ABCDE            device usb:1-1 product:redfin model:Pixel_5 device:redfin transport_id:3

        """.trimIndent()

        val results = DeviceListParser.parse(output)
        results.size shouldBe 2

        val devices = DeviceListParser.parseDevices(output)
        devices.size shouldBe 1
        devices[0].state shouldBe DeviceConnectionState.Online
    }

    @Test
    fun `unrecognized state string is surfaced as a typed Unknown state, not a crash`() {
        val output = "List of devices attached\nR58N90ABCDE            bootloader\n"

        val results = DeviceListParser.parse(output)

        results shouldBe listOf(
            AdbParseResult.Parsed(
                Device(
                    serial = DeviceSerial.of("R58N90ABCDE"),
                    state = DeviceConnectionState.Unknown("bootloader"),
                ),
            ),
        )
    }

    @Test
    fun `no permissions state spans two tokens and trailing hint text is ignored, not treated as a field`() {
        val output = "List of devices attached\n0123456789ABCDEF       no permissions (user in plugdev group?); see [http://x] usb:1-1\n"

        val results = DeviceListParser.parse(output)

        results shouldBe listOf(
            AdbParseResult.Parsed(
                Device(
                    serial = DeviceSerial.of("0123456789ABCDEF"),
                    state = DeviceConnectionState.NoPermissions,
                ),
            ),
        )
    }

    @Test
    fun `a malformed line with only a serial and no state does not crash the whole parse`() {
        val output = """
            List of devices attached
            R58N90ABCDE            device usb:1-1 product:redfin model:Pixel_5 device:redfin transport_id:3
            garbled-line-with-no-state

        """.trimIndent()

        val results = DeviceListParser.parse(output)

        results.size shouldBe 2
        results[0].shouldBeInstanceOf<AdbParseResult.Parsed<Device>>()
        val malformed = results[1] as AdbParseResult.Malformed
        malformed.raw shouldBe "garbled-line-with-no-state"
    }

    @Test
    fun `blank output parses to an empty list without throwing`() {
        DeviceListParser.parse("") shouldBe emptyList()
        DeviceListParser.parse("   \n  \n").shouldBe(emptyList())
    }
}
