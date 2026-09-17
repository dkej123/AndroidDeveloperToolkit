package dev.acme.adbtoolbox.domain.devicecontext

/**
 * The result of one feature's reset/re-apply attempt for one serial (task 041). A coordinator
 * (e.g. [dev.acme.adbtoolbox.application.devicecontext.OverrideResetCoordinator]) never collapses
 * a multi-feature reset-all/re-apply into one pass/fail — every feature's own [OverrideResetOutcome]
 * stays individually visible so a partial failure (e.g. proxy reset ok, density reset denied) is
 * never hidden.
 */
sealed interface OverrideResetOutcome {

    /** The device confirmed, via readback, that this feature's override was reset/re-applied. */
    data object Success : OverrideResetOutcome

    /** Nothing was applied for this feature/serial, so there was nothing to reset/re-apply. */
    data object NoOverride : OverrideResetOutcome

    /** The command failed, timed out, was cancelled, or the readback did not confirm the value. */
    data class Failed(val reason: String) : OverrideResetOutcome
}
