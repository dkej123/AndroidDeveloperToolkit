package dev.acme.adbtoolbox.application.display

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummary
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("AAAA111")

private class FixedOverrides(private val summaries: List<OverrideSummary>) : OverrideSummaryContributor {
    override fun overridesFor(serial: DeviceSerial?): List<OverrideSummary> = if (serial == null) emptyList() else summaries
}

class DisplayBadgeContributorTest {

    @Test
    fun `targets the Display view`() {
        DisplayBadgeContributor(FixedOverrides(emptyList()), FixedOverrides(emptyList())).viewId shouldBe ViewId.Display
    }

    @Test
    fun `no badge when neither font scale nor density is overridden`() {
        val contributor = DisplayBadgeContributor(FixedOverrides(emptyList()), FixedOverrides(emptyList()))

        contributor.badgeFor(serial) shouldBe NavigationBadge.None
    }

    @Test
    fun `attention badge when font scale alone is overridden`() {
        val contributor = DisplayBadgeContributor(
            FixedOverrides(listOf(OverrideSummary("font-scale", "Font scale: 1.3x"))),
            FixedOverrides(emptyList()),
        )

        contributor.badgeFor(serial) shouldBe NavigationBadge.Attention
    }

    @Test
    fun `attention badge when density alone is overridden`() {
        val contributor = DisplayBadgeContributor(
            FixedOverrides(emptyList()),
            FixedOverrides(listOf(OverrideSummary("density", "Density: 125% (525 dpi)"))),
        )

        contributor.badgeFor(serial) shouldBe NavigationBadge.Attention
    }

    @Test
    fun `still a single attention badge when both are overridden`() {
        val contributor = DisplayBadgeContributor(
            FixedOverrides(listOf(OverrideSummary("font-scale", "Font scale: 1.3x"))),
            FixedOverrides(listOf(OverrideSummary("density", "Density: 125% (525 dpi)"))),
        )

        contributor.badgeFor(serial) shouldBe NavigationBadge.Attention
    }

    @Test
    fun `no badge when serial is null`() {
        val contributor = DisplayBadgeContributor(
            FixedOverrides(listOf(OverrideSummary("font-scale", "Font scale: 1.3x"))),
            FixedOverrides(emptyList()),
        )

        contributor.badgeFor(null) shouldBe NavigationBadge.None
    }
}
