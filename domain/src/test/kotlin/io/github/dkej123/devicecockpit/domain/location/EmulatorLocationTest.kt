package io.github.dkej123.devicecockpit.domain.location

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EmulatorLocationTest {

    @Test
    fun `parses latitude then longitude in the usual separators`() {
        GeoPoint.parse("52.2297, 21.0122") shouldBe GeoPoint.of(52.2297, 21.0122)
        GeoPoint.parse("52.2297 21.0122") shouldBe GeoPoint.of(52.2297, 21.0122)
        GeoPoint.parse("-33.86;151.2") shouldBe GeoPoint.of(-33.86, 151.2)
    }

    @Test
    fun `rejects out-of-range and incomplete positions`() {
        listOf("", "52.2", "91, 0", "0, 181", "a, b", "1, 2, 3").forEach { GeoPoint.parse(it) shouldBe null }
        GeoPoint.of(Double.NaN, 0.0) shouldBe null
    }

    @Test
    fun `sends longitude first to the emulator console`() {
        val serial = DeviceSerial.of("emulator-5554")
        val request = EmulatorLocationCommand.request(serial, GeoPoint.of(52.2297, 21.0122)!!)

        (request.operation as AdbOperation.Host).arguments shouldBe listOf("emu", "geo", "fix", "21.0122", "52.2297")
    }

    @Test
    fun `only emulator serials are supported and only OK is accepted`() {
        EmulatorLocationCommand.isSupported(DeviceSerial.of("emulator-5556")) shouldBe true
        EmulatorLocationCommand.isSupported(DeviceSerial.of("192.168.1.5:5555")) shouldBe false
        EmulatorLocationCommand.isAccepted(AdbTextResult(AdbOutcome.Completed(0), "OK\n", "")) shouldBe true
        EmulatorLocationCommand.isAccepted(AdbTextResult(AdbOutcome.Completed(0), "KO: bad\n", "")) shouldBe false
    }

    @Test
    fun `the presets include Warsaw`() {
        EmulatorLocationCommand.cities.first { it.name == "Warsaw" }.point shouldBe GeoPoint.of(52.2297, 21.0122)
    }
}
