package dev.acme.adbtoolbox.application.apps

/**
 * User-triggered inputs [AppLifecycleViewModel] reduces (ADR 0004) — the Apps-view action footer's
 * Force-stop/Launch/Restart buttons, and the global restart shortcut/action, all forward the exact
 * same [Restart] intent to the same shared view model instance (task 023's scope: the global action
 * must share the same use case/entry point as the panel button, never a duplicate implementation).
 */
sealed interface AppLifecycleIntent {
    data object ForceStop : AppLifecycleIntent
    data object Launch : AppLifecycleIntent
    data object Restart : AppLifecycleIntent
}
