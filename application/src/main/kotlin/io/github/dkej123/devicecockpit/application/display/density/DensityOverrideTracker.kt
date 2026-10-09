package io.github.dkej123.devicecockpit.application.display.density

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.devicecontext.OverrideSummary
import io.github.dkej123.devicecockpit.domain.devicecontext.OverrideSummaryContributor
import io.github.dkej123.devicecockpit.domain.display.density.DensityReading
import kotlin.math.roundToInt

private const val OVERRIDE_ID = "density"

/**
 * Density's feature-local [OverrideSummaryContributor] (task 014): holds the last known-good
 * per-serial [DensityReading] (updated only from an actual `wm density` readback by
 * [DensityUseCase], never from a request value) and reports an override summary only for a serial
 * whose latest reading currently carries an override dpi.
 */
class DensityOverrideTracker : OverrideSummaryContributor {

    private var readings: Map<DeviceSerial, DensityReading> = emptyMap()

    /** Records the latest readback truth for [serial]; removes any prior entry when unset. */
    fun record(serial: DeviceSerial, reading: DensityReading) {
        readings = if (reading.overrideDpi != null) {
            readings + (serial to reading)
        } else {
            readings - serial
        }
    }

    /** The last recorded override dpi for [serial] (task 041's [OverrideResetUseCase.currentValue] source), or `null`. */
    fun overrideDpiFor(serial: DeviceSerial): Int? = readings[serial]?.overrideDpi

    override fun overridesFor(serial: DeviceSerial?): List<OverrideSummary> {
        val reading = serial?.let { readings[it] } ?: return emptyList()
        val overrideDpi = reading.overrideDpi ?: return emptyList()
        val percent = (overrideDpi * 100.0 / reading.physicalDpi).roundToInt()
        return listOf(OverrideSummary(OVERRIDE_ID, "Density: $percent% ($overrideDpi dpi)"))
    }
}
