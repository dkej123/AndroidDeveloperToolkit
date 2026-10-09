package io.github.dkej123.devicecockpit.application.display.fontscale

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.devicecontext.OverrideSummary
import io.github.dkej123.devicecockpit.domain.devicecontext.OverrideSummaryContributor
import io.github.dkej123.devicecockpit.domain.display.fontscale.FontScalePresets
import io.github.dkej123.devicecockpit.domain.display.fontscale.formatFontScale

/**
 * The task 014 [OverrideSummaryContributor] for font scale. [FontScaleViewModel] calls [record]
 * with every successful device-truth readback (never the last-requested value); this holds only
 * this feature's own per-serial state — never a shared mutable map another feature writes into —
 * matching [io.github.dkej123.devicecockpit.application.devicecontext.DeviceContextAggregator]'s "feature
 * packages own their own state" contract.
 */
class FontScaleOverrides : OverrideSummaryContributor {

    private val lastReadback = mutableMapOf<DeviceSerial, Double>()

    /** Records [value] as the last device-truth font-scale readback for [serial]. */
    fun record(serial: DeviceSerial, value: Double) {
        lastReadback[serial] = value
    }

    /** The last recorded readback for [serial] (task 041's [OverrideResetUseCase.currentValue] source), or `null`. */
    fun lastValueFor(serial: DeviceSerial): Double? = lastReadback[serial]

    override fun overridesFor(serial: DeviceSerial?): List<OverrideSummary> {
        val value = serial?.let(lastReadback::get) ?: return emptyList()
        if (value == FontScalePresets.DEFAULT) return emptyList()
        return listOf(OverrideSummary(id = OVERRIDE_ID, description = "Font scale: ${formatFontScale(value)}×"))
    }

    companion object {
        const val OVERRIDE_ID = "font-scale"
    }
}
