package dev.acme.adbtoolbox.domain.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The shape of one rail entry's reserved badge input (`design/README.md` §2: "5px dot ... amber on
 * Display when font scale != 1 or density != 100% ... red on Logcat when errors are arriving").
 * This task defines the SHAPE only — which values exist, not which [ViewId] gets which value, what
 * counts as "an override," or any color/icon. Later feature tasks (Display/Network/Logcat, 043+)
 * decide those business rules and call [MutableNavigationBadges.set] from their own file.
 */
sealed interface NavigationBadge {
    /** No badge — the default, unset state for every [ViewId]. */
    data object None : NavigationBadge

    /** A numeric badge (e.g. "3 issues"). The count's meaning is entirely feature-owned. */
    data class Count(val value: Int) : NavigationBadge

    /** A non-numeric "something needs attention" indicator (e.g. unseen Logcat error lines). */
    data object Attention : NavigationBadge
}

/**
 * A small aggregate contract keyed by [ViewId] (task 012's scope: "reserve feature badge inputs
 * through a small aggregate contract"). One instance is composed at the root (`:intellij`'s
 * composition, task 007) and handed to both the navigation rail (to render — task 043+, not this
 * one) and to each feature ViewModel (to populate its own [ViewId]'s entry, locally, without ever
 * editing this file or a shared router).
 */
interface NavigationBadges {
    val state: StateFlow<Map<ViewId, NavigationBadge>>
}

/** The default in-memory [NavigationBadges] implementation: a plain observable map, no rules. */
class MutableNavigationBadges : NavigationBadges {
    private val _state = MutableStateFlow<Map<ViewId, NavigationBadge>>(emptyMap())
    override val state: StateFlow<Map<ViewId, NavigationBadge>> = _state.asStateFlow()

    /** Sets [viewId]'s badge to [badge]; [NavigationBadge.None] is equivalent to clearing it. */
    fun set(viewId: ViewId, badge: NavigationBadge) {
        _state.value = if (badge == NavigationBadge.None) {
            _state.value - viewId
        } else {
            _state.value + (viewId to badge)
        }
    }
}
