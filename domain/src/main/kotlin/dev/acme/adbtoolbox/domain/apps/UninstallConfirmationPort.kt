package dev.acme.adbtoolbox.domain.apps

interface UninstallConfirmationPort {
    suspend fun confirmUninstall(packageName: String, deviceLabel: String): UninstallConfirmation
}

sealed interface UninstallConfirmation {
    data object Confirmed : UninstallConfirmation
    data object Cancelled : UninstallConfirmation
}
