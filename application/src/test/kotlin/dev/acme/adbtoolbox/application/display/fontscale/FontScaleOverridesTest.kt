package dev.acme.adbtoolbox.application.display.fontscale

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummary
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FontScaleOverridesTest {

    private val serialA = DeviceSerial.of("AAAA111")
    private val serialB = DeviceSerial.of("BBBB222")

    @Test
    fun `no readback recorded yet contributes nothing`() {
        val overrides = FontScaleOverrides()

        overrides.overridesFor(serialA) shouldBe emptyList()
    }

    @Test
    fun `a null serial contributes nothing`() {
        val overrides = FontScaleOverrides()
        overrides.record(serialA, 1.3)

        overrides.overridesFor(null) shouldBe emptyList()
    }

    @Test
    fun `a readback at the platform default contributes nothing`() {
        val overrides = FontScaleOverrides()
        overrides.record(serialA, 1.0)

        overrides.overridesFor(serialA) shouldBe emptyList()
    }

    @Test
    fun `a readback overridden from the platform default contributes a summary`() {
        val overrides = FontScaleOverrides()
        overrides.record(serialA, 1.3)

        overrides.overridesFor(serialA) shouldBe listOf(
            OverrideSummary(id = FontScaleOverrides.OVERRIDE_ID, description = "Font scale: 1.3×"),
        )
    }

    @Test
    fun `overrides are tracked independently per serial and never mixed`() {
        val overrides = FontScaleOverrides()
        overrides.record(serialA, 1.3)
        overrides.record(serialB, 1.0)

        overrides.overridesFor(serialA) shouldBe listOf(
            OverrideSummary(id = FontScaleOverrides.OVERRIDE_ID, description = "Font scale: 1.3×"),
        )
        overrides.overridesFor(serialB) shouldBe emptyList()
    }
}
