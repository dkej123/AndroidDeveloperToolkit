package dev.acme.adbtoolbox.domain.display.density

/**
 * One `wm density` reading: [physicalDpi] is always reported; [overrideDpi] is non-null only when
 * the device currently reports an "Override density" line, i.e. a per-serial override is applied.
 */
data class DensityReading(val physicalDpi: Int, val overrideDpi: Int?)
