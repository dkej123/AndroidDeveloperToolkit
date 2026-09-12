package dev.acme.adbtoolbox.domain.display.density

import kotlin.math.floor

/**
 * Resolves a density preset/custom percentage relative to [physicalDpi] into an absolute dpi.
 *
 * Rounding rule: **round-half-up** (ties round away from zero) — chosen over banker's rounding
 * (round-half-to-even) because it matches Android's own `Math.round` behavior (what a user
 * mentally expects "428 dpi at 125%" to resolve to) and, critically, is the single deterministic
 * rule this project applies everywhere a percentage becomes a dpi: presets, custom values, and the
 * safe-range boundaries in [validateCustomDensity] all resolve through this same function, so a
 * requested preset's tooltip value and its post-readback value can never silently disagree because
 * two different rounding rules were used to compute them.
 */
fun percentToDpi(physicalDpi: Int, percent: Int): Int {
    val exact = physicalDpi.toDouble() * percent / 100.0
    return floor(exact + 0.5).toInt()
}
