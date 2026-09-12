package dev.acme.adbtoolbox.application.devicebar

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceConnectionKind
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState

/**
 * One row of the device picker (task 011), keyed by its exact [serial] — never by [model]/[product]
 * name or list position — so two devices sharing the same model name, or a USB and a Wi-Fi serial
 * for otherwise-identical-looking devices, never collide or get selected by the wrong identity.
 */
data class DevicePickerItem(
    val serial: DeviceSerial,
    val model: String?,
    val product: String?,
    val connectionKind: DeviceConnectionKind,
    val connectionState: DeviceConnectionState,
    val isSelected: Boolean,
)
