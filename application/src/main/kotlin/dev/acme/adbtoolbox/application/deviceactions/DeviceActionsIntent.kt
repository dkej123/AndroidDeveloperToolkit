package dev.acme.adbtoolbox.application.deviceactions

/** User-triggered inputs [DeviceActionsViewModel] reduces against [DeviceActionsViewState] (ADR 0004). */
sealed interface DeviceActionsIntent {
    data object Reboot : DeviceActionsIntent
    data object Wake : DeviceActionsIntent
    data object OpenShell : DeviceActionsIntent
}
