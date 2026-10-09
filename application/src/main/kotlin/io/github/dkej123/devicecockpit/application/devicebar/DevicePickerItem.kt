package io.github.dkej123.devicecockpit.application.devicebar

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionKind
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState

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
