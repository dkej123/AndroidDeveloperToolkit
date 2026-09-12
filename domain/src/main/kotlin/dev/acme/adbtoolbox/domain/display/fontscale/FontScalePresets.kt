package dev.acme.adbtoolbox.domain.display.fontscale

/**
 * Approved font-scale chip presets (`design/tokens/tokens.json` → `presets.fontScale`) and the
 * platform default `settings put system font_scale` resets to. [DEFAULT] is `1.0`, matching Android's
 * unscaled font size.
 */
object FontScalePresets {
    val VALUES: List<Double> = listOf(0.85, 1.0, 1.15, 1.3, 1.5, 2.0)
    const val DEFAULT: Double = 1.0
}

/** The approved custom-value range (`design/tokens/tokens.json` → `presets.fontScaleRange`). */
object FontScaleRange {
    const val MIN: Double = 0.25
    const val MAX: Double = 5.0
}
