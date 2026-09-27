package dev.acme.adbtoolbox.application.display

import dev.acme.adbtoolbox.domain.display.TalkBackProfile

import dev.acme.adbtoolbox.domain.display.AnimationsSummary

/**
 * The Display view's Quick toggles section state (task 028, `design/README.md` §5). [talkBackProfile]
 * names whose commands the TalkBack toggle runs (`null` while unknown, or when no known TalkBack is
 * installed and Settings has no custom command).
 */
data class QuickTogglesViewState(
    val darkTheme: QuickToggleFieldState<Boolean> = QuickToggleFieldState.Loading,
    val showTouches: QuickToggleFieldState<Boolean> = QuickToggleFieldState.Loading,
    val animations: QuickToggleFieldState<AnimationsSummary> = QuickToggleFieldState.Loading,
    val talkBack: QuickToggleFieldState<Boolean> = QuickToggleFieldState.Loading,
    val talkBackProfile: TalkBackProfile? = null,
)
