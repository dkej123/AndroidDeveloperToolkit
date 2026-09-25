package dev.acme.adbtoolbox.domain.device

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * One ADB-visible device as parsed from `adb devices -l`, keyed by its exact [serial] (task 008:
 * "all devices retain stable explicit serials and truthful state — never fabricated/coerced").
 * [product], [model], [device] and [transportId] are the optional `key:value` columns `adb devices
 * -l` appends after the state column; any of them may be absent (older adb, or a non-online device
 * that reports none of them).
 */
data class Device(
    val serial: DeviceSerial,
    val state: DeviceConnectionState,
    val product: String? = null,
    val model: String? = null,
    val device: String? = null,
    val transportId: String? = null,
) {
    val connectionKind: DeviceConnectionKind get() = DeviceConnectionKind.of(serial.toString())

    /**
     * The name a user knows the device by. `adb devices -l` cannot print spaces inside a field, so
     * [model] arrives underscore-joined ("Pixel_8_Pro"); this restores the spaces. Falls back to the
     * [serial] when adb reports no model. [model] itself stays the raw adb token.
     */
    val displayName: String get() = model?.replace('_', ' ') ?: serial.toString()
}
