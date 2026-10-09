package io.github.dkej123.devicecockpit.application.nav

import io.github.dkej123.devicecockpit.domain.nav.ViewId

/** User/system-triggered inputs [NavigationViewModel] reduces against [io.github.dkej123.devicecockpit.domain.nav.NavigationState] (ADR 0004). */
sealed interface NavigationIntent {
    /** Explicit selection of exactly [viewId] — via rail click, keyboard traversal, or restore. */
    data class Select(val viewId: ViewId) : NavigationIntent

    /** Retries resolving the persisted last view after a [io.github.dkej123.devicecockpit.domain.nav.NavigationState.Error]. */
    data object RetryRestore : NavigationIntent
}
