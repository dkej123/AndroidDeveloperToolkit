package dev.acme.adbtoolbox.application.display

/** User intents for the Display view's Quick toggles section (task 028). */
sealed interface QuickTogglesIntent {
    data class SetDarkTheme(val enabled: Boolean) : QuickTogglesIntent

    data class SetShowTouches(val enabled: Boolean) : QuickTogglesIntent

    data class SetAnimationsOff(val off: Boolean) : QuickTogglesIntent

    data object Refresh : QuickTogglesIntent
}
