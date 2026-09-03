package dev.acme.adbtoolbox.domain.nav

/**
 * The one selected-view context for a project (task 012), reduced from a persisted/user-chosen
 * [ViewId] the same way [dev.acme.adbtoolbox.domain.device.SelectedDeviceState] is reduced from a
 * persisted serial (ADR 0004). Restoration always resolves to a concrete [ViewId] — missing or
 * corrupt persisted data degrades to a caller-supplied default (`:application`'s
 * `NavigationViewModel`) rather than ever leaving navigation destination-less.
 */
sealed interface NavigationState {

    /** The persisted last view has not resolved yet — no destination can be rendered as active. */
    data object Loading : NavigationState

    /** [selected] is the current destination: either restored, defaulted, or explicitly chosen. */
    data class Ready(val selected: ViewId) : NavigationState

    /** A recoverable failure resolving the persisted last view (e.g. an unexpected read failure). */
    data class Error(val message: String) : NavigationState
}
