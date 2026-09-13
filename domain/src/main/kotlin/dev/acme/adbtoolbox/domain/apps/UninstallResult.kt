package dev.acme.adbtoolbox.domain.apps

/** Truthful outcomes of one serial/package-scoped uninstall request. */
sealed interface UninstallResult {
    data object Success : UninstallResult
    data class AlreadyMissing(val diagnostics: String) : UninstallResult
    data class Failure(val reason: String) : UninstallResult
    data object TimedOut : UninstallResult
    data object Cancelled : UninstallResult
    data object RejectedDuplicate : UninstallResult
}
