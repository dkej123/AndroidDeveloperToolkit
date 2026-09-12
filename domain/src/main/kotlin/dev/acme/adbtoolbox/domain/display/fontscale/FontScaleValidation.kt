package dev.acme.adbtoolbox.domain.display.fontscale

/** The outcome of validating a font-scale value (preset or custom) before ever issuing an ADB command. */
sealed interface FontScaleValidationResult {
    data class Valid(val value: Double) : FontScaleValidationResult
    data class OutOfRange(val value: Double, val min: Double, val max: Double) : FontScaleValidationResult
}

/**
 * Validates [value] against [FontScaleRange] — the boundary presets/custom entries share — so an
 * out-of-range custom value is rejected before any command is built, per task 026's acceptance
 * criterion that override state must reflect device truth, never an unvalidated request.
 */
fun validateFontScale(value: Double): FontScaleValidationResult =
    if (value in FontScaleRange.MIN..FontScaleRange.MAX) {
        FontScaleValidationResult.Valid(value)
    } else {
        FontScaleValidationResult.OutOfRange(value, FontScaleRange.MIN, FontScaleRange.MAX)
    }
