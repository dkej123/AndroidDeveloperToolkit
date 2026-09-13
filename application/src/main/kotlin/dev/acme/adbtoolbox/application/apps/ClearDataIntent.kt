package dev.acme.adbtoolbox.application.apps

/** User-triggered inputs for task 024's isolated destructive workflow. */
sealed interface ClearDataIntent {
    data object ClearData : ClearDataIntent
}
