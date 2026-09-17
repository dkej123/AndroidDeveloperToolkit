package dev.acme.adbtoolbox.application.display.density

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummary
import dev.acme.adbtoolbox.domain.display.density.DensityReading
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val SERIAL = DeviceSerial.of("emulator-5554")

class DensityOverrideTrackerTest {

    @Test
    fun `no summary is reported until a reading with an override is recorded`() {
        val tracker = DensityOverrideTracker()

        tracker.overridesFor(SERIAL) shouldBe emptyList()
    }

    @Test
    fun `a reading with an override dpi is reported as one override summary`() {
        val tracker = DensityOverrideTracker()

        tracker.record(SERIAL, DensityReading(physicalDpi = 420, overrideDpi = 525))

        tracker.overridesFor(SERIAL) shouldBe listOf(OverrideSummary("density", "Density: 125% (525 dpi)"))
    }

    @Test
    fun `recording a reading with no override clears a prior override for that serial`() {
        val tracker = DensityOverrideTracker()
        tracker.record(SERIAL, DensityReading(420, 525))

        tracker.record(SERIAL, DensityReading(420, null))

        tracker.overridesFor(SERIAL) shouldBe emptyList()
    }

    @Test
    fun `a null serial reports no overrides`() {
        val tracker = DensityOverrideTracker()
        tracker.record(SERIAL, DensityReading(420, 525))

        tracker.overridesFor(null) shouldBe emptyList()
    }

    @Test
    fun `an override for a different serial is never reported`() {
        val tracker = DensityOverrideTracker()
        tracker.record(SERIAL, DensityReading(420, 525))

        tracker.overridesFor(DeviceSerial.of("other-serial")) shouldBe emptyList()
    }

    @Test
    fun `overrideDpiFor returns the last recorded override dpi, or null when none is applied`() {
        val tracker = DensityOverrideTracker()

        tracker.overrideDpiFor(SERIAL) shouldBe null

        tracker.record(SERIAL, DensityReading(420, 525))
        tracker.overrideDpiFor(SERIAL) shouldBe 525

        tracker.record(SERIAL, DensityReading(420, null))
        tracker.overrideDpiFor(SERIAL) shouldBe null
    }
}
