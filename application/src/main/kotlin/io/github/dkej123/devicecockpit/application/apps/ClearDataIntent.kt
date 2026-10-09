package io.github.dkej123.devicecockpit.application.apps

/** User-triggered inputs for task 024's isolated destructive workflow. */
sealed interface ClearDataIntent {
    data object ClearData : ClearDataIntent
}
