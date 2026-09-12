package dev.acme.adbtoolbox.domain.display

/**
 * The three independent Android global animation-scale settings the "Animations off" quick toggle
 * writes and reads (`design/IMPLEMENTATION.md` §4). They are genuinely independent — a device can
 * have any combination of values across the three simultaneously — which is why they are modeled
 * as three separate settings rather than one, and why [AnimationsSummary] keeps a [Mixed] case.
 */
enum class AnimationScaleSetting(val settingName: String) {
    WINDOW("window_animation_scale"),
    TRANSITION("transition_animation_scale"),
    ANIMATOR_DURATION("animator_duration_scale"),
}
