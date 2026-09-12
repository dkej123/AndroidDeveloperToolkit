package dev.acme.adbtoolbox.domain.display.density

/**
 * Display-density presets and safety bounds (design/README.md §5: chips `80% 90% 100% 110% 125%
 * 150%`; "Values outside 60-200% can make the UI unusable"). All values are percentages of the
 * physical density, resolved to an absolute dpi via [percentToDpi].
 */
object DensityPresets {
    val PERCENTAGES: List<Int> = listOf(80, 90, 100, 110, 125, 150)

    const val SAFE_RANGE_MIN_PERCENT: Int = 60
    const val SAFE_RANGE_MAX_PERCENT: Int = 200
}
