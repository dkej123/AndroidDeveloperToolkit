package dev.acme.adbtoolbox.application.mirroring

/**
 * The single user/system-triggered input [MirroringViewModel] reduces against (ADR 0004): both
 * task 018's Device-view button and its global keyboard-shortcut action forward the exact same
 * [Toggle] intent to the exact same [MirroringViewModel.handle] entry point, so the two triggers
 * can never diverge into two different start/stop code paths.
 */
sealed interface MirroringIntent {
    data object Toggle : MirroringIntent
}
