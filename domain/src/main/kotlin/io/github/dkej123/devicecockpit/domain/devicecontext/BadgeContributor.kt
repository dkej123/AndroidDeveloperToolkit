package io.github.dkej123.devicecockpit.domain.devicecontext

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.nav.NavigationBadge
import io.github.dkej123.devicecockpit.domain.nav.ViewId

/**
 * A feature-local contribution port (task 014): a feature package (Logcat, Display, ...) implements
 * this against its *own* state and registers it with [DeviceContextAggregator][io.github.dkej123.devicecockpit.application.devicecontext.DeviceContextAggregator]
 * — it never writes into a shared badge map itself. Reuses [NavigationBadge] (task 012) as the value
 * vocabulary so a badge means the same thing whether it reached the rail through this aggregator or
 * through [io.github.dkej123.devicecockpit.domain.nav.MutableNavigationBadges] directly.
 */
interface BadgeContributor {

    /** The rail destination this contributor's badge applies to. */
    val viewId: ViewId

    /** The badge to show for [serial] right now (`null` when no device is selected). Pull-based: the aggregator calls this at aggregation time; it is never pushed. */
    fun badgeFor(serial: DeviceSerial?): NavigationBadge
}
