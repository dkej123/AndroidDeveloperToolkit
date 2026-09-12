package dev.acme.adbtoolbox.application.network

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.network.ProxyReadState

/**
 * The immutable proxy state for the currently selected serial (task 030). [readState] is device
 * truth only — set from a `settings get global http_proxy` readback, never from the requested
 * host/port a caller last typed (the same readback-is-truth principle as tasks 026/027). `null`
 * means no successful read has landed yet for [serial] (no device selected, or still loading).
 */
data class ProxyViewState(
    val serial: DeviceSerial? = null,
    val readState: ProxyReadState? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
)
