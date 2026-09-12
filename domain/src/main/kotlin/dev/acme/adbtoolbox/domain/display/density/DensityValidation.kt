package dev.acme.adbtoolbox.domain.display.density

/**
 * The outcome of validating a custom absolute-dpi request against the safe range before any
 * command is issued (design/README.md §5).
 */
sealed interface DensityValidationResult {
    data class Valid(val dpi: Int) : DensityValidationResult

    data class OutOfRange(val requestedDpi: Int, val minDpi: Int, val maxDpi: Int) : DensityValidationResult
}

/**
 * Rejects an unsafely low/high absolute dpi before any `wm density` command is built. The safe
 * range is [DensityPresets.SAFE_RANGE_MIN_PERCENT]-[DensityPresets.SAFE_RANGE_MAX_PERCENT] of
 * [physicalDpi], resolved with the same [percentToDpi] rounding rule the presets use, inclusive of
 * both boundaries.
 */
fun validateCustomDensity(physicalDpi: Int, requestedDpi: Int): DensityValidationResult {
    val minDpi = percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MIN_PERCENT)
    val maxDpi = percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MAX_PERCENT)
    return if (requestedDpi in minDpi..maxDpi) {
        DensityValidationResult.Valid(requestedDpi)
    } else {
        DensityValidationResult.OutOfRange(requestedDpi, minDpi, maxDpi)
    }
}
