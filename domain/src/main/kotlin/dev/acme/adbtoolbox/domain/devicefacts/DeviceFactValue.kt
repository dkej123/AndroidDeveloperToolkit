package dev.acme.adbtoolbox.domain.devicefacts

import kotlin.time.Duration

/** The typed value of one successfully parsed [DeviceFactId] — one variant per fact. */
sealed interface DeviceFactValue {
    data class AndroidVersion(val release: String, val sdk: Int) : DeviceFactValue

    data class Resolution(val widthPx: Int, val heightPx: Int) : DeviceFactValue

    data class Density(val dpi: Int) : DeviceFactValue

    /** [levelPercent] is `0..100`; [charging] is derived from `dumpsys battery`'s `status` field. */
    data class Battery(val levelPercent: Int, val charging: Boolean) : DeviceFactValue

    data class Abi(val value: String) : DeviceFactValue

    data class Uptime(val duration: Duration) : DeviceFactValue
}
