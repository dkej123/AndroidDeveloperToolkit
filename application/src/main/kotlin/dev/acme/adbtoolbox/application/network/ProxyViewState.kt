package dev.acme.adbtoolbox.application.network

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyReadState

/**
 * The immutable proxy state for the currently selected serial (task 030), extended by task 032 with
 * live-editing/recents/host-IP-resolution state. [readState] is device truth only — set from a
 * `settings get global http_proxy` readback, never from the requested host/port a caller last typed
 * (the same readback-is-truth principle as tasks 026/027). `null` means no successful read has
 * landed yet for [serial] (no device selected, or still loading).
 *
 * [hostInput]/[portInput] are the current (uncommitted) form text, updated on every keystroke via
 * [ProxyIntent.EditHost]/[ProxyIntent.EditPort] or filled without enabling via
 * [ProxyIntent.SelectRecent]/[ProxyIntent.UseComputerIp] — never auto-submitted. [hostError]/
 * [portError] are live per-field validation messages (`null` while the respective field is blank or
 * valid); [canSubmit] is `true` only when both fields are non-blank and valid, matching
 * `design/README.md` §6's "invalid port ... the primary action is disabled." [recents] is the
 * project-scoped MRU list (task 032, ADR 0006's `recentProxies`), preserved across device switches
 * since it is not per-serial. [isResolvingIp] reflects an in-flight "Use my computer IP" lookup.
 */
data class ProxyViewState(
    val serial: DeviceSerial? = null,
    val readState: ProxyReadState? = null,
    val isDeviceEligible: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val hostInput: String = "",
    val portInput: String = "",
    val hostError: String? = null,
    val portError: String? = null,
    val recents: List<ProxyEndpoint> = emptyList(),
    val isResolvingIp: Boolean = false,
) {
    val canSubmit: Boolean
        get() = hostInput.isNotBlank() && portInput.isNotBlank() && hostError == null && portError == null
}
