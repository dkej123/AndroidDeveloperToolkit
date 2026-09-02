package dev.acme.adbtoolbox.application.shell

/**
 * The immutable state of the plugin's neutral tool-window content (task 007's placeholder — no
 * real feature; real feature ViewStates land per-feature from task 011 onward, per ADR 0004).
 * Replaced wholesale on every reduction, never mutated in place.
 */
data class ShellViewState(
    val statusMessage: String = "Ready",
    val isRefreshing: Boolean = false,
)
