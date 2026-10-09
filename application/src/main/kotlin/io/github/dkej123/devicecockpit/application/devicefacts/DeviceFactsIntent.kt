package io.github.dkej123.devicecockpit.application.devicefacts

/** User/system-triggered inputs [DeviceFactsViewModel] reduces against [DeviceFactsViewState] (ADR 0004). */
sealed interface DeviceFactsIntent {
    /** Formats the current snapshot as a report and writes it to the clipboard (`design/README.md`'s "Copy report"). A no-op if no snapshot exists yet. */
    data object CopyReport : DeviceFactsIntent

    /** Re-reads every fact for the selected device in place (the current values stay shown meanwhile). */
    data object Refresh : DeviceFactsIntent
}
