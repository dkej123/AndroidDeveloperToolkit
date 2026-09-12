package dev.acme.adbtoolbox.application.network

/** User-triggered inputs [ProxyController] reduces against [ProxyViewState] (ADR 0004). */
sealed interface ProxyIntent {
    /** Raw text-field input, validated client-side before any command is issued (task 030). */
    data class Enable(val hostInput: String, val portInput: String) : ProxyIntent

    data object Reset : ProxyIntent
}
