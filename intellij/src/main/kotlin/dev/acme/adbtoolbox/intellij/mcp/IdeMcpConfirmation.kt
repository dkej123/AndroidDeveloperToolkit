package dev.acme.adbtoolbox.intellij.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindowManager
import dev.acme.adbtoolbox.application.mcp.tools.DestructiveAppAction
import dev.acme.adbtoolbox.application.mcp.tools.McpConfirmation
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.intellij.apps.AppsConfirmationDialog
import dev.acme.adbtoolbox.intellij.apps.clearDataDialogSpec
import dev.acme.adbtoolbox.intellij.apps.uninstallDialogSpec
import javax.swing.Timer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Uninstall / Clear data requested by an agent (design §11): the tool window's own confirmation with
 * "Requested by … over MCP", the tool window shown if hidden; Cancel or 60 s without an answer is a
 * decline.
 */
class IdeMcpConfirmation(private val project: Project, private val deviceLabel: (DeviceSerial) -> String) : McpConfirmation {
    override suspend fun confirm(action: DestructiveAppAction, serial: DeviceSerial, packageName: String, agent: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            ApplicationManager.getApplication().invokeLater({
                ToolWindowManager.getInstance(project).getToolWindow("ADB Toolbox")?.show()
                val (title, body, ok) = when (action) {
                    DestructiveAppAction.ClearData -> clearDataDialogSpec(packageName, deviceLabel(serial)).let { Triple(it.title, it.message, "Clear data") }
                    DestructiveAppAction.Uninstall -> uninstallDialogSpec(packageName, deviceLabel(serial)).let { Triple(it.title, it.message, "Uninstall") }
                }
                val dialog = AppsConfirmationDialog(project, title, body, ok, agentNote = "Requested by $agent over MCP. Cancel tells the agent you declined.")
                val timeout = Timer(TIMEOUT_MS) { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }.apply {
                    isRepeats = false
                    start()
                }
                val confirmed = dialog.showAndGet()
                timeout.stop()
                if (continuation.isActive) continuation.resume(confirmed)
            }, ModalityState.any())
        }

    private companion object {
        const val TIMEOUT_MS = 60_000
    }
}
