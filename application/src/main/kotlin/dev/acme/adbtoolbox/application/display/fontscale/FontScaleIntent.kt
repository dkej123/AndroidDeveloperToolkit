package dev.acme.adbtoolbox.application.display.fontscale

/** User intents [FontScaleViewModel] handles for the currently eligible device. */
sealed interface FontScaleIntent {

    /** Apply [value] — a preset or a custom value; validated before any command is issued. */
    data class Apply(val value: Double) : FontScaleIntent

    /** Reset to the platform default. */
    data object Reset : FontScaleIntent

    /** Re-read the current value, e.g. to recover from an [dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState.Error]. */
    data object Retry : FontScaleIntent
}
