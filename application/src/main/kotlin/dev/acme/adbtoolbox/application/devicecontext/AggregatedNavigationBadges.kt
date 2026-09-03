package dev.acme.adbtoolbox.application.devicecontext

import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.NavigationBadges
import dev.acme.adbtoolbox.domain.nav.ViewId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A [NavigationBadges] backed by a [DeviceContextAggregator]'s [BadgeContributor][dev.acme.adbtoolbox.domain.devicecontext.BadgeContributor]
 * registrations (task 014), rather than task 012's push-based
 * [dev.acme.adbtoolbox.domain.nav.MutableNavigationBadges.set]. The composition root (task 007)
 * picks whichever [NavigationBadges] implementation it wants the rail to read from; a feature that
 * already owns a [DeviceContextAggregator] registration for other reasons (running processes,
 * overrides) can publish its badge through the same registration instead of a second push call.
 */
class AggregatedNavigationBadges(scope: CoroutineScope, aggregator: DeviceContextAggregator) : NavigationBadges {
    override val state: StateFlow<Map<ViewId, NavigationBadge>> =
        aggregator.state
            .map { it.badges }
            .stateIn(scope, SharingStarted.Eagerly, aggregator.state.value.badges)
}
