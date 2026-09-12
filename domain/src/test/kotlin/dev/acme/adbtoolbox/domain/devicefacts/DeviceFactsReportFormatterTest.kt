package dev.acme.adbtoolbox.domain.devicefacts

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Test

class DeviceFactsReportFormatterTest {

    private val serial = DeviceSerial.of("R58N90ABCDE")

    @Test
    fun `formats a fully available snapshot deterministically`() {
        val snapshot = DeviceFactsSnapshot(
            serial = serial,
            facts = mapOf(
                DeviceFactId.AndroidVersion to DeviceFactState.Available(DeviceFactValue.AndroidVersion("15", 35)),
                DeviceFactId.Resolution to DeviceFactState.Available(DeviceFactValue.Resolution(1080, 2400)),
                DeviceFactId.Density to DeviceFactState.Available(DeviceFactValue.Density(428)),
                DeviceFactId.Battery to DeviceFactState.Available(DeviceFactValue.Battery(72, charging = true)),
                DeviceFactId.Abi to DeviceFactState.Available(DeviceFactValue.Abi("arm64-v8a")),
                DeviceFactId.Uptime to DeviceFactState.Available(DeviceFactValue.Uptime(4.hours + 12.minutes)),
            ),
        )

        DeviceFactsReportFormatter.format(snapshot) shouldBe """
            Device: R58N90ABCDE
            Android: 15 · API 35
            Resolution: 1080x2400
            Density: 428 dpi
            Battery: 72% · charging
            ABI: arm64-v8a
            Uptime: 4h 12m
        """.trimIndent()
    }

    @Test
    fun `an unavailable fact renders as Unavailable, never omitted or blank`() {
        val snapshot = DeviceFactsSnapshot(
            serial = serial,
            facts = mapOf(
                DeviceFactId.AndroidVersion to DeviceFactState.Available(DeviceFactValue.AndroidVersion("15", 35)),
                DeviceFactId.Resolution to DeviceFactState.Unavailable("malformed output"),
                DeviceFactId.Density to DeviceFactState.Loading,
                DeviceFactId.Battery to DeviceFactState.Available(DeviceFactValue.Battery(72, charging = false)),
                DeviceFactId.Abi to DeviceFactState.Available(DeviceFactValue.Abi("arm64-v8a")),
                DeviceFactId.Uptime to DeviceFactState.Unavailable("transport failure"),
            ),
        )

        val report = DeviceFactsReportFormatter.format(snapshot)

        report shouldBe """
            Device: R58N90ABCDE
            Android: 15 · API 35
            Resolution: Unavailable
            Density: Loading…
            Battery: 72% · not charging
            ABI: arm64-v8a
            Uptime: Unavailable
        """.trimIndent()
    }
}
