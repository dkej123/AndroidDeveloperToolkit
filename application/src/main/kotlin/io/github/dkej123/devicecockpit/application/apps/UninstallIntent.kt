package io.github.dkej123.devicecockpit.application.apps

sealed interface UninstallIntent {
    data object Uninstall : UninstallIntent
}
