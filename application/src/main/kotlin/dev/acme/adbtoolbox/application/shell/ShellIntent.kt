package dev.acme.adbtoolbox.application.shell

/** User/system-triggered inputs [ShellViewModel] reduces against [ShellViewState] (ADR 0004). */
sealed interface ShellIntent {
    /** Placeholder intent proving the MVI seam compiles and reduces end to end (task 007). */
    data object Refresh : ShellIntent
}
