package dev.acme.adbtoolbox.domain.apps

/**
 * The platform-neutral destructive-confirmation port for task 024's Clear-data action
 * (`design/README.md` §4's "the only two modals in the plugin" spec): implemented by exactly one
 * `:intellij` adapter showing a platform confirmation dialog whose Cancel action is focused and
 * default, whose destructive OK action is never the default, and whose Escape key cancels — never
 * inherited from `references/ADBHelper`'s `ClearAppDataAction`, which runs `pm clear` with no
 * confirmation at all.
 *
 * [confirmClearData] is `suspend` (mirrors
 * [dev.acme.adbtoolbox.domain.deviceactions.TerminalLauncher]'s shape) because showing a modal
 * dialog and waiting for the user's choice is itself a suspending operation from the caller's
 * point of view; no command may be issued before this call returns [ClearDataConfirmation.Confirmed].
 */
interface ClearDataConfirmationPort {
    suspend fun confirmClearData(packageName: String, deviceLabel: String): ClearDataConfirmation
}

/** What the user chose in task 024's Clear-data confirmation dialog. */
sealed interface ClearDataConfirmation {
    data object Confirmed : ClearDataConfirmation
    data object Cancelled : ClearDataConfirmation
}
