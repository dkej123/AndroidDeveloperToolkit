package dev.acme.adbtoolbox.domain.display

/**
 * The honest, three-value-aware read of the "Animations off" toggle (task 028's #1 acceptance
 * criterion: "Mixed/partial state is never flattened into false success"). [AllOff]/[AllOn] are the
 * two states our own toggle writes; [Mixed] carries the three actual values whenever they disagree
 * with each other, or agree at a value other than the toggle's own 0/1 — e.g. a device with
 * window=0, transition=1, animator=0.5 is [Mixed], never coerced to a single boolean.
 */
sealed interface AnimationsSummary {
    data object AllOff : AnimationsSummary

    data object AllOn : AnimationsSummary

    data class Mixed(val window: Float, val transition: Float, val animatorDuration: Float) : AnimationsSummary
}

/**
 * The result of reading all three [AnimationScaleSetting]s together and reconciling them
 * (`design/README.md` §5's "Animations off"). Folds three independent [DisplaySettingRead] values
 * into one outcome so a caller need not hand-merge three parse results at every call site.
 */
sealed interface AnimationsReadOutcome {
    data class Ready(val summary: AnimationsSummary) : AnimationsReadOutcome

    data class Unsupported(val settings: List<AnimationScaleSetting>) : AnimationsReadOutcome

    data class PermissionDenied(val message: String) : AnimationsReadOutcome

    data class Malformed(val raw: String) : AnimationsReadOutcome

    data class TransportFailed(val outcome: dev.acme.adbtoolbox.domain.adb.AdbOutcome) : AnimationsReadOutcome
}

/**
 * Combines the three [AnimationScaleSetting] reads into one [AnimationsReadOutcome]. Priority when
 * more than one setting failed differently: [DisplaySettingRead.TransportFailed] (the read never
 * reached the device at all) beats [DisplaySettingRead.PermissionDenied], which beats
 * [DisplaySettingRead.Malformed], which beats [DisplaySettingRead.NotSet] — each is a strictly less
 * recoverable failure than the next, and only when all three are genuine values does this derive
 * [AnimationsSummary].
 */
fun combineAnimationReads(
    window: DisplaySettingRead<Float>,
    transition: DisplaySettingRead<Float>,
    animatorDuration: DisplaySettingRead<Float>,
): AnimationsReadOutcome {
    val bySetting = listOf(
        AnimationScaleSetting.WINDOW to window,
        AnimationScaleSetting.TRANSITION to transition,
        AnimationScaleSetting.ANIMATOR_DURATION to animatorDuration,
    )

    bySetting.firstNotNullOfOrNull { (_, read) -> (read as? DisplaySettingRead.TransportFailed)?.outcome }
        ?.let { return AnimationsReadOutcome.TransportFailed(it) }
    bySetting.firstNotNullOfOrNull { (_, read) -> (read as? DisplaySettingRead.PermissionDenied)?.message }
        ?.let { return AnimationsReadOutcome.PermissionDenied(it) }
    bySetting.firstNotNullOfOrNull { (_, read) -> (read as? DisplaySettingRead.Malformed)?.raw }
        ?.let { return AnimationsReadOutcome.Malformed(it) }

    val unsupported = bySetting.filter { (_, read) -> read is DisplaySettingRead.NotSet }.map { it.first }
    if (unsupported.isNotEmpty()) return AnimationsReadOutcome.Unsupported(unsupported)

    val windowValue = (window as DisplaySettingRead.Value).value
    val transitionValue = (transition as DisplaySettingRead.Value).value
    val animatorValue = (animatorDuration as DisplaySettingRead.Value).value

    val summary = when {
        windowValue == 0f && transitionValue == 0f && animatorValue == 0f -> AnimationsSummary.AllOff
        windowValue == 1f && transitionValue == 1f && animatorValue == 1f -> AnimationsSummary.AllOn
        else -> AnimationsSummary.Mixed(windowValue, transitionValue, animatorValue)
    }
    return AnimationsReadOutcome.Ready(summary)
}
