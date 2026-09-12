package dev.acme.adbtoolbox.domain.devicefacts

import dev.acme.adbtoolbox.domain.adb.AdbParseResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Test

class DeviceFactsParsersTest {

    // -- Android version --------------------------------------------------------------------

    @Test
    fun `android version parses release and sdk`() {
        val result = DeviceFactsParsers.parseAndroidVersion("15\n35\n")
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.AndroidVersion("15", 35))
    }

    @Test
    fun `android version tolerates CRLF line endings`() {
        val result = DeviceFactsParsers.parseAndroidVersion("15\r\n35\r\n")
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.AndroidVersion("15", 35))
    }

    @Test
    fun `android version is malformed when the sdk line is missing`() {
        val result = DeviceFactsParsers.parseAndroidVersion("15\n")
        result.shouldBeInstanceOf<AdbParseResult.Malformed>()
    }

    @Test
    fun `android version is malformed when the sdk line is not numeric`() {
        val result = DeviceFactsParsers.parseAndroidVersion("15\nUnknown\n")
        result.shouldBeInstanceOf<AdbParseResult.Malformed>()
    }

    @Test
    fun `android version is malformed on a permission-denied response`() {
        val result = DeviceFactsParsers.parseAndroidVersion("Permission denied\n")
        result.shouldBeInstanceOf<AdbParseResult.Malformed>()
    }

    // -- Resolution ---------------------------------------------------------------------------

    @Test
    fun `resolution parses physical size`() {
        val result = DeviceFactsParsers.parseResolution("Physical size: 1080x2400\n")
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Resolution(1080, 2400))
    }

    @Test
    fun `resolution prefers override size over physical size`() {
        val raw = "Physical size: 1080x2400\r\nOverride size: 1080x2160\r\n"
        val result = DeviceFactsParsers.parseResolution(raw)
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Resolution(1080, 2160))
    }

    @Test
    fun `resolution is malformed on unrecognized output`() {
        val result = DeviceFactsParsers.parseResolution("Error: Could not access the Package Manager\n")
        result.shouldBeInstanceOf<AdbParseResult.Malformed>()
    }

    // -- Density --------------------------------------------------------------------------------

    @Test
    fun `density parses physical density`() {
        val result = DeviceFactsParsers.parseDensity("Physical density: 420\n")
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Density(420))
    }

    @Test
    fun `density prefers override density (OEM variant with extra whitespace)`() {
        val raw = "Physical density: 420\nOverride density:   480  \n"
        val result = DeviceFactsParsers.parseDensity(raw)
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Density(480))
    }

    @Test
    fun `density is malformed on unrecognized output`() {
        val result = DeviceFactsParsers.parseDensity("nonsense\n")
        result.shouldBeInstanceOf<AdbParseResult.Malformed>()
    }

    // -- Battery --------------------------------------------------------------------------------

    private val batteryFixture = """
        Current Battery Service state:
          AC powered: false
          USB powered: true
          status: 2
          health: 2
          present: true
          level: 72
          scale: 100
          voltage: 4200
          temperature: 250
          technology: Li-ion
    """.trimIndent()

    @Test
    fun `battery parses level and charging status`() {
        val result = DeviceFactsParsers.parseBattery(batteryFixture)
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Battery(72, charging = true))
    }

    @Test
    fun `battery treats status full as charging`() {
        val raw = "level: 100\nscale: 100\nstatus: 5\n"
        val result = DeviceFactsParsers.parseBattery(raw)
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Battery(100, charging = true))
    }

    @Test
    fun `battery treats status discharging as not charging`() {
        val raw = "level: 42\nscale: 100\nstatus: 3\n"
        val result = DeviceFactsParsers.parseBattery(raw)
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Battery(42, charging = false))
    }

    @Test
    fun `battery is malformed when required fields are missing`() {
        val result = DeviceFactsParsers.parseBattery("garbage output\n")
        result.shouldBeInstanceOf<AdbParseResult.Malformed>()
    }

    // -- ABI ------------------------------------------------------------------------------------

    @Test
    fun `abi parses a single line`() {
        val result = DeviceFactsParsers.parseAbi("arm64-v8a\n")
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Abi("arm64-v8a"))
    }

    @Test
    fun `abi is malformed on blank output`() {
        val result = DeviceFactsParsers.parseAbi("\n\n")
        result.shouldBeInstanceOf<AdbParseResult.Malformed>()
    }

    // -- Uptime ---------------------------------------------------------------------------------

    @Test
    fun `uptime parses hours and minutes`() {
        val raw = " 14:32:01 up 4:12,  0 users,  load average: 0.10, 0.05, 0.01\n"
        val result = DeviceFactsParsers.parseUptime(raw)
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Uptime(4.hours + 12.minutes))
    }

    @Test
    fun `uptime parses a multi-day OEM-variant line`() {
        val raw = "07:41:05 up 3 days, 22:12,  0 users,  load average: 0.00, 0.00, 0.00\r\n"
        val result = DeviceFactsParsers.parseUptime(raw)
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Uptime(72.hours + 22.hours + 12.minutes))
    }

    @Test
    fun `uptime parses a minutes-only freshly-booted variant`() {
        val raw = "21:11:32 up 5 min,  0 users,  load average: 0.10, 0.19, 0.22\n"
        val result = DeviceFactsParsers.parseUptime(raw)
        result shouldBe AdbParseResult.Parsed(DeviceFactValue.Uptime(5.minutes))
    }

    @Test
    fun `uptime is malformed on unrecognized output`() {
        val result = DeviceFactsParsers.parseUptime("not an uptime line\n")
        result.shouldBeInstanceOf<AdbParseResult.Malformed>()
    }
}
