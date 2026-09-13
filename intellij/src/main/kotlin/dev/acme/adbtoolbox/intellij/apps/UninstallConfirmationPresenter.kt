package dev.acme.adbtoolbox.intellij.apps

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.acme.adbtoolbox.domain.apps.UninstallConfirmation
import dev.acme.adbtoolbox.domain.apps.UninstallConfirmationPort
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.withContext

internal data class UninstallDialogSpec(
    val title: String,
    val message: String,
    val options: List<String> = listOf("Cancel", "Uninstall"),
    val defaultOptionIndex: Int = 0,
    val focusedOptionIndex: Int = 0,
    val cancelOptionIndex: Int = 0,
    val destructiveOptionIndex: Int = 1,
)

internal fun uninstallDialogSpec(packageName: String, deviceLabel: String): UninstallDialogSpec =
    UninstallDialogSpec(
        title = "Uninstall $packageName?",
        message = "Removes the app and all of its data from $deviceLabel. This cannot be undone.",
    )

internal fun uninstallConfirmationForDialogResult(
    result: Int,
    spec: UninstallDialogSpec,
): UninstallConfirmation =
    if (result == spec.destructiveOptionIndex) UninstallConfirmation.Confirmed else UninstallConfirmation.Cancelled

/** IntelliJ adapter for task 025's sole pre-command uninstall confirmation. */
internal class UninstallConfirmationPresenter(
    private val project: Project,
    private val dispatchers: DispatcherProvider,
    private val showDialog: (Project, UninstallDialogSpec) -> Int = ::showUninstallDialog,
) : UninstallConfirmationPort {

    override suspend fun confirmUninstall(packageName: String, deviceLabel: String): UninstallConfirmation =
        withContext(dispatchers.main) {
            val spec = uninstallDialogSpec(packageName, deviceLabel)
            uninstallConfirmationForDialogResult(showDialog(project, spec), spec)
        }
}

private fun showUninstallDialog(project: Project, spec: UninstallDialogSpec): Int = Messages.showDialog(
    project,
    spec.message,
    spec.title,
    null,
    spec.options.toTypedArray(),
    spec.defaultOptionIndex,
    spec.focusedOptionIndex,
    Messages.getWarningIcon(),
)
