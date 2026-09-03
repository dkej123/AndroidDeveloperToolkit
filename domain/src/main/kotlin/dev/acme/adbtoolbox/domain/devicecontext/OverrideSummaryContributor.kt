package dev.acme.adbtoolbox.domain.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/** One per-serial applied-override summary (e.g. "Font scale: 1.3x (overridden)"), display text only. */
data class OverrideSummary(val id: String, val description: String)

/**
 * A feature-local contribution port (task 014): a feature owning an overridable device setting
 * (Display's font scale/density, Network's proxy, ...) implements this against its own applied-
 * overrides state and registers it with `DeviceContextAggregator`, so the status bar's combined "N
 * overrides · reset all" chip can read one aggregated list without owning any feature's override
 * state itself. This task defines the summary contract only — applying/resetting overrides is task
 * 041's job, out of scope here.
 */
interface OverrideSummaryContributor {

    /** The overrides this contributor currently has applied for [serial] (`null` when no device is selected). */
    fun overridesFor(serial: DeviceSerial?): List<OverrideSummary>
}
