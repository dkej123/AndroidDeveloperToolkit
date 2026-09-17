package dev.acme.adbtoolbox.domain.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * A feature-local "reset / re-apply" port (task 041): a feature that owns an overridable device
 * setting (Display's font scale/density, Network's proxy, ...) implements this against its own
 * existing apply/reset logic and registers it with a coordinator (e.g.
 * [dev.acme.adbtoolbox.application.devicecontext.OverrideResetCoordinator]) so reset-all and
 * reconnect re-apply can drive every feature without any feature owning another feature's state,
 * and without the coordinator ever issuing a raw ADB command itself.
 *
 * [featureId] matches the `id` the same feature publishes via
 * [OverrideSummaryContributor]/[OverrideSummary.id] (e.g. `"font-scale"`, `"density"`, `"proxy"`).
 */
interface OverrideResetUseCase {

    val featureId: String

    /**
     * The last device-truth override value for [serial] as an opaque, feature-owned token (e.g.
     * `"1.3"` for font scale, `"160"` for a density dpi, `"10.0.2.2:8080"` for a proxy endpoint),
     * or `null` when this feature has no override currently applied for [serial]. Never the last
     * *requested* value — only what a readback has actually confirmed (mirrors
     * [OverrideSummaryContributor.overridesFor]'s truth rule).
     */
    fun currentValue(serial: DeviceSerial): String?

    /** Resets this feature's override on [serial] to the platform default, reading back to confirm. */
    suspend fun reset(serial: DeviceSerial): OverrideResetOutcome

    /**
     * Re-applies a previously remembered [value] (as returned by [currentValue] before [serial]
     * went offline) to [serial], reading back to confirm — never called automatically, only after
     * explicit user approval of a re-apply offer.
     */
    suspend fun reapply(serial: DeviceSerial, value: String): OverrideResetOutcome
}
