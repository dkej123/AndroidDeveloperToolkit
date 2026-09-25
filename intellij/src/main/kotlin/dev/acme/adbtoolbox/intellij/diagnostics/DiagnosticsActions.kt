package dev.acme.adbtoolbox.intellij.diagnostics

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.acme.adbtoolbox.adapters.jvm.diagnostics.ThreadSamplingProfiler
import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path

/** Entry points shared by the actions, the tool-window gear menu and the Settings page. */
object DiagnosticsActions {
    const val NOTIFICATION_GROUP = "ADB Toolbox Diagnostics"
    private const val RECORDING_SECONDS = 60L

    fun collect(project: Project?) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Collecting ADB Toolbox diagnostics", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val zip = DiagnosticsCollector.collect(project)
                notify(
                    project,
                    "Diagnostics saved",
                    "${zip.fileName} — attach this file when reporting the problem.",
                    NotificationType.INFORMATION,
                    revealAction(zip),
                    NotificationAction.createSimple("Copy path") { CopyPasteManager.getInstance().setContents(StringSelection(zip.toString())) },
                )
            }
        })
    }

    fun recordPerformance(project: Project?) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Recording ADB Toolbox performance (${RECORDING_SECONDS} s)", true) {
            override fun run(indicator: ProgressIndicator) {
                val service = DiagnosticsService.getInstance()
                service.log.log(DiagLevel.INFO, DiagCategory.PERF, "performance recording started", mapOf("seconds" to RECORDING_SECONDS))
                indicator.isIndeterminate = false
                val report = ThreadSamplingProfiler(intervalMs = 200, durationMs = RECORDING_SECONDS * 1000).run(
                    isCancelled = indicator::isCanceled,
                    onProgress = { indicator.fraction = it },
                )
                val file = service.logDirectory.resolve("perf-${DiagnosticsCollector.timestamp()}.txt")
                Files.createDirectories(file.parent)
                Files.writeString(file, report)
                service.log.log(DiagLevel.INFO, DiagCategory.PERF, "performance recording saved", mapOf("path" to file))
                notify(
                    project,
                    "Performance recording saved",
                    "${file.fileName}. Collect diagnostics to include it in a bundle.",
                    NotificationType.INFORMATION,
                    NotificationAction.createSimpleExpiring("Collect Diagnostics…") { collect(project) },
                    revealAction(file),
                )
            }
        })
    }

    fun openLogFolder() {
        val directory = DiagnosticsService.getInstance().logDirectory
        Files.createDirectories(directory)
        RevealFileAction.openDirectory(directory)
    }

    private fun revealAction(path: Path) =
        NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openFile(path) }

    private fun notify(project: Project?, title: String, content: String, type: NotificationType, vararg actions: AnAction) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .apply { actions.forEach(::addAction) }
            .notify(project)
    }
}

class CollectDiagnosticsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(event: AnActionEvent) = DiagnosticsActions.collect(event.project)
}

class RecordPerformanceAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(event: AnActionEvent) = DiagnosticsActions.recordPerformance(event.project)
}

class OpenDiagnosticsLogFolderAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(event: AnActionEvent) = DiagnosticsActions.openLogFolder()
}
