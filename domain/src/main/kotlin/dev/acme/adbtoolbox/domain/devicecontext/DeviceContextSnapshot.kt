package dev.acme.adbtoolbox.domain.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId

/**
 * One immutable, exact-serial-scoped combination of whatever [BadgeContributor]/
 * [RunningProcessContributor]/[OverrideSummaryContributor]s are currently registered (task 014).
 * Every field is a plain immutable `Map`/`List` snapshot — nothing here is ever mutated in place;
 * a new [DeviceContextSnapshot] is produced by [aggregateDeviceContext] each time inputs change.
 */
data class DeviceContextSnapshot(
    val serial: DeviceSerial?,
    val badges: Map<ViewId, NavigationBadge>,
    val runningProcesses: List<RunningProcessInfo>,
    val overrides: List<OverrideSummary>,
)

/**
 * Combines the currently registered contributors into one [DeviceContextSnapshot] for [serial].
 * Pure and synchronous: each contributor is queried with [serial] directly (never an implicit
 * "current device" the contributor tracks itself), so there is no window in which a contributor
 * could answer for a different serial than the one requested — the no-device-mixing guarantee is
 * structural, not merely tested. [NavigationBadge.None] entries are omitted, matching
 * [dev.acme.adbtoolbox.domain.nav.MutableNavigationBadges]'s "unset" convention.
 */
fun aggregateDeviceContext(
    serial: DeviceSerial?,
    badgeContributors: List<BadgeContributor>,
    processContributors: List<RunningProcessContributor>,
    overrideContributors: List<OverrideSummaryContributor>,
): DeviceContextSnapshot {
    val badges = badgeContributors
        .associate { contributor -> contributor.viewId to contributor.badgeFor(serial) }
        .filterValues { it != NavigationBadge.None }
    val runningProcesses = processContributors.flatMap { it.runningProcessesFor(serial) }
    val overrides = overrideContributors.flatMap { it.overridesFor(serial) }
    return DeviceContextSnapshot(serial, badges, runningProcesses, overrides)
}
