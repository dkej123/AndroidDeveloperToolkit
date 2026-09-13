package dev.acme.adbtoolbox.application.apps

sealed interface UninstallIntent {
    data object Uninstall : UninstallIntent
}
