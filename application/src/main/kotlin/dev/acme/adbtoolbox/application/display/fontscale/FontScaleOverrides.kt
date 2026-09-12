package dev.acme.adbtoolbox.application.display.fontscale

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummary
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.display.fontscale.FontScalePresets
import dev.acme.adbtoolbox.domain.display.fontscale.formatFontScale

/**
 * The task 014 [OverrideSummaryContributor] for font scale. [FontScaleViewModel] calls [record]
 * with every successful device-truth readback (never the last-requested value); this holds only
 * this feature's own per-serial state — never a shared mutable map another feature writes into —
 * matching [dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator]'s "feature
 * packages own their own state" contract.
 */
class FontScaleOverrides : OverrideSummaryContributor {

    private val lastReadback = mutableMapOf<DeviceSerial, Double>()

    /** Records [value] as the last device-truth font-scale readback for [serial]. */
    fun record(serial: DeviceSerial, value: Double) {
        lastReadback[serial] = value
    }

    override fun overridesFor(serial: DeviceSerial?): List<OverrideSummary> {
        val value = serial?.let(lastReadback::get) ?: return emptyList()
        if (value == FontScalePresets.DEFAULT) return emptyList()
        return listOf(OverrideSummary(id = OVERRIDE_ID, description = "Font scale: ${formatFontScale(value)}×"))
    }

    companion object {
        const val OVERRIDE_ID = "font-scale"
    }
}
