package dev.acme.adbtoolbox.application.nav

import dev.acme.adbtoolbox.domain.nav.ViewId

/** User/system-triggered inputs [NavigationViewModel] reduces against [dev.acme.adbtoolbox.domain.nav.NavigationState] (ADR 0004). */
sealed interface NavigationIntent {
    /** Explicit selection of exactly [viewId] — via rail click, keyboard traversal, or restore. */
    data class Select(val viewId: ViewId) : NavigationIntent

    /** Retries resolving the persisted last view after a [dev.acme.adbtoolbox.domain.nav.NavigationState.Error]. */
    data object RetryRestore : NavigationIntent
}
