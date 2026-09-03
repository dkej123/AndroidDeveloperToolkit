package dev.acme.adbtoolbox.domain.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId

/**
 * A feature-local contribution port (task 014): a feature package (Logcat, Display, ...) implements
 * this against its *own* state and registers it with [DeviceContextAggregator][dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator]
 * — it never writes into a shared badge map itself. Reuses [NavigationBadge] (task 012) as the value
 * vocabulary so a badge means the same thing whether it reached the rail through this aggregator or
 * through [dev.acme.adbtoolbox.domain.nav.MutableNavigationBadges] directly.
 */
interface BadgeContributor {

    /** The rail destination this contributor's badge applies to. */
    val viewId: ViewId

    /** The badge to show for [serial] right now (`null` when no device is selected). Pull-based: the aggregator calls this at aggregation time; it is never pushed. */
    fun badgeFor(serial: DeviceSerial?): NavigationBadge
}
