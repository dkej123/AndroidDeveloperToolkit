package dev.acme.adbtoolbox.intellij.apps

import com.intellij.openapi.project.Project
import dev.acme.adbtoolbox.domain.apps.ClearDataConfirmation
import dev.acme.adbtoolbox.domain.apps.ClearDataConfirmationPort
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.withContext

internal data class ClearDataDialogSpec(
    val title: String,
    val message: String,
    val options: List<String> = listOf("Cancel", "Clear data"),
    val defaultOptionIndex: Int = 0,
    val focusedOptionIndex: Int = 0,
    val cancelOptionIndex: Int = 0,
    val destructiveOptionIndex: Int = 1,
)

internal fun clearDataDialogSpec(packageName: String, deviceLabel: String): ClearDataDialogSpec = ClearDataDialogSpec(
    title = "Clear data for $packageName?",
    message = "Deletes databases, preferences and caches on $deviceLabel, and signs the user out. " +
        "This cannot be undone.",
)

internal fun confirmationForDialogResult(result: Int, spec: ClearDataDialogSpec): ClearDataConfirmation =
    if (result == spec.destructiveOptionIndex) ClearDataConfirmation.Confirmed else ClearDataConfirmation.Cancelled

/** IntelliJ adapter for task 024's sole pre-command clear-data confirmation. */
internal class ClearDataConfirmationPresenter(
    private val project: Project,
    private val dispatchers: DispatcherProvider,
    private val showDialog: (Project, ClearDataDialogSpec) -> Int = ::showClearDataDialog,
) : ClearDataConfirmationPort {

    override suspend fun confirmClearData(packageName: String, deviceLabel: String): ClearDataConfirmation =
        withContext(dispatchers.main) {
            val spec = clearDataDialogSpec(packageName, deviceLabel)
            // The default Cancel button, Escape, and closing the window all map to cancellation.
            confirmationForDialogResult(showDialog(project, spec), spec)
        }
}

private fun showClearDataDialog(project: Project, spec: ClearDataDialogSpec): Int {
    val dialog = AppsConfirmationDialog(
        project = project,
        dialogTitle = spec.title,
        bodyText = spec.message,
        destructiveLabel = spec.options[spec.destructiveOptionIndex],
    )
    return if (dialog.showAndGet()) spec.destructiveOptionIndex else spec.cancelOptionIndex
}
