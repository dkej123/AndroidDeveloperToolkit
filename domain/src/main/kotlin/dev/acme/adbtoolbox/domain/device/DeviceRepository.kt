package dev.acme.adbtoolbox.domain.device

import kotlinx.coroutines.flow.StateFlow

/**
 * Observable device discovery (task 008): the unified, deduplicated list of ADB-visible devices,
 * refreshed by whatever mix of ddmlib hotplug events and binary `adb devices -l` polling the
 * implementation composes (ADR 0005). Kept as a `:domain` port so a future feature (device
 * bar/picker, task 011) depends on this contract rather than on `:adapters-adb`'s
 * `AdbDeviceRepository` directly.
 */
interface DeviceRepository {
    val devices: StateFlow<List<Device>>
}
