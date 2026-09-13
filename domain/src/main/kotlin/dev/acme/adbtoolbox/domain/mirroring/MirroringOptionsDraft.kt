package dev.acme.adbtoolbox.domain.mirroring

/**
 * The unvalidated, freely-constructible shape of [MirroringOptions] (task 040): unlike
 * [MirroringOptions] itself, this never throws, so a UI form or a persisted-but-corrupt value can be
 * held here — even wildly out of range — until [validateMirroringOptions] decides whether it may
 * become a real [MirroringOptions].
 */
data class MirroringOptionsDraft(
    val stayAwake: Boolean = false,
    val showTouches: Boolean = false,
    val maxSize: Int? = null,
    val videoBitRateMbps: Int? = null,
) {
    companion object {
        val DEFAULT: MirroringOptionsDraft = MirroringOptionsDraft()
    }
}

/** [MirroringOptions] is always already-valid, so its draft projection never needs validation. */
fun MirroringOptions.toDraft(): MirroringOptionsDraft = MirroringOptionsDraft(
    stayAwake = stayAwake,
    showTouches = showTouches,
    maxSize = maxSize,
    videoBitRateMbps = videoBitRateMbps,
)

/** Why a candidate [MirroringOptionsDraft] was rejected by [validateMirroringOptions] — invalid
 * options must never silently persist or start a session (task 040's acceptance criterion). */
sealed interface MirroringOptionFieldError {
    data object MaxSizeOutOfRange : MirroringOptionFieldError
    data object VideoBitRateOutOfRange : MirroringOptionFieldError
}

sealed interface MirroringOptionsValidationResult {
    data class Valid(val options: MirroringOptions) : MirroringOptionsValidationResult
    data class Invalid(val errors: Set<MirroringOptionFieldError>) : MirroringOptionsValidationResult
}

/**
 * Validates [candidate] before it is ever turned into a [MirroringOptions] (which would otherwise
 * throw on an out-of-range value reached mid-edit) or persisted. A `null` [MirroringOptionsDraft.maxSize]/
 * [MirroringOptionsDraft.videoBitRateMbps] always passes (matches scrcpy's own "flag omitted"
 * semantics); a non-null value must fall inside [MirroringOptions]'s declared bounds. Collects every
 * failing field rather than stopping at the first, matching [dev.acme.adbtoolbox.domain.settings.validateSettings]'s contract.
 */
fun validateMirroringOptions(candidate: MirroringOptionsDraft): MirroringOptionsValidationResult {
    val errors = mutableSetOf<MirroringOptionFieldError>()

    candidate.maxSize?.let {
        if (it !in MirroringOptions.MIN_MAX_SIZE_PX..MirroringOptions.MAX_MAX_SIZE_PX) {
            errors += MirroringOptionFieldError.MaxSizeOutOfRange
        }
    }
    candidate.videoBitRateMbps?.let {
        if (it !in MirroringOptions.MIN_VIDEO_BIT_RATE_MBPS..MirroringOptions.MAX_VIDEO_BIT_RATE_MBPS) {
            errors += MirroringOptionFieldError.VideoBitRateOutOfRange
        }
    }

    return if (errors.isNotEmpty()) {
        MirroringOptionsValidationResult.Invalid(errors)
    } else {
        MirroringOptionsValidationResult.Valid(
            MirroringOptions(
                stayAwake = candidate.stayAwake,
                showTouches = candidate.showTouches,
                maxSize = candidate.maxSize,
                videoBitRateMbps = candidate.videoBitRateMbps,
            ),
        )
    }
}
