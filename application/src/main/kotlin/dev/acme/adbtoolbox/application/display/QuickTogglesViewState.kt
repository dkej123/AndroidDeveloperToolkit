package dev.acme.adbtoolbox.application.display

import dev.acme.adbtoolbox.domain.display.AnimationsSummary

/** The Display view's Quick toggles section state (task 028, `design/README.md` §5). */
data class QuickTogglesViewState(
    val darkTheme: QuickToggleFieldState<Boolean> = QuickToggleFieldState.Loading,
    val showTouches: QuickToggleFieldState<Boolean> = QuickToggleFieldState.Loading,
    val animations: QuickToggleFieldState<AnimationsSummary> = QuickToggleFieldState.Loading,
)
