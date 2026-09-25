package dev.acme.adbtoolbox.domain.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable device discovery (task 008): the unified, deduplicated list of ADB-visible devices,
 * refreshed by whatever mix of ddmlib hotplug events and binary `adb devices -l` polling the
 * implementation composes (ADR 0005). Kept as a `:domain` port so a future feature (device
 * bar/picker, task 011) depends on this contract rather than on `:adapters-adb`'s
 * `AdbDeviceRepository` directly.
 */
interface DeviceRepository {
    val devices: StateFlow<List<Device>>

    /**
     * Why the most recent device-list query failed (adb not found, adb exited with an error, …), or
     * `null` after a successful query. [devices] keeps its last good value on failure, so without
     * this the UI could not tell "no device attached" apart from "device discovery is broken".
     */
    val listError: StateFlow<String?> get() = NO_LIST_ERROR
}

private val NO_LIST_ERROR: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
