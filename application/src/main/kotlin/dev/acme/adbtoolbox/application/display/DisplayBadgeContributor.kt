package dev.acme.adbtoolbox.application.display

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.BadgeContributor
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId

/**
 * The Display rail entry's task 014 [BadgeContributor] (`design/README.md` §2: "amber ... on
 * Display when font scale != 1 or density != 100%"). Rather than tracking font-scale/density
 * values a second time, this reuses [fontScaleOverrides]/[densityOverrides] — the same task 026/027
 * [OverrideSummaryContributor]s the status bar's override chip already reads — as the single source
 * of "is this serial currently overridden," so the badge and the override chip can never disagree
 * about what counts as an override.
 */
class DisplayBadgeContributor(
    private val fontScaleOverrides: OverrideSummaryContributor,
    private val densityOverrides: OverrideSummaryContributor,
) : BadgeContributor {

    override val viewId: ViewId = ViewId.Display

    override fun badgeFor(serial: DeviceSerial?): NavigationBadge =
        if (fontScaleOverrides.overridesFor(serial).isNotEmpty() || densityOverrides.overridesFor(serial).isNotEmpty()) {
            NavigationBadge.Attention
        } else {
            NavigationBadge.None
        }
}
