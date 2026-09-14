package dev.acme.adbtoolbox.application.display.density

/** User intents [DensityViewModel] handles for the currently eligible device (task 029). */
sealed interface DensityIntent {

    /** Apply a preset percentage of the physical density (`design/README.md` §5's chip row). */
    data class ApplyPreset(val percent: Int) : DensityIntent

    /** Apply a custom absolute dpi; validated against the safe range before any command is issued. */
    data class ApplyCustom(val dpi: Int) : DensityIntent

    /** Reset to the physical density. */
    data object Reset : DensityIntent

    /** Re-read the current value, e.g. to recover from a [DensityViewState.Error]. */
    data object Retry : DensityIntent
}
