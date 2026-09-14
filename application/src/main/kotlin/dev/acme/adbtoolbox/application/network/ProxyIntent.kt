package dev.acme.adbtoolbox.application.network

import dev.acme.adbtoolbox.domain.network.ProxyEndpoint

/** User-triggered inputs [ProxyController] reduces against [ProxyViewState] (ADR 0004). */
sealed interface ProxyIntent {
    /** Raw text-field input, validated client-side before any command is issued (task 030). */
    data class Enable(val hostInput: String, val portInput: String) : ProxyIntent

    data object Reset : ProxyIntent

    /** Live host-field text as the user types (task 032) — never issues a command by itself. */
    data class EditHost(val text: String) : ProxyIntent

    /** Live port-field text as the user types (task 032) — never issues a command by itself. */
    data class EditPort(val text: String) : ProxyIntent

    /** Fills the host/port fields from a recent entry (`design/README.md` §6: "Clicking a row fills
     * host+port without enabling") — never enqueues an Enable by itself. */
    data class SelectRecent(val endpoint: ProxyEndpoint) : ProxyIntent

    /** Resolves and fills the host field with this machine's LAN IPv4 (`design/README.md` §6's "Use
     * my computer IP") — never enqueues an Enable by itself. */
    data object UseComputerIp : ProxyIntent
}
