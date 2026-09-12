package dev.acme.adbtoolbox.intellij.apps

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import dev.acme.adbtoolbox.application.apps.AppLifecycleIntent
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService

/**
 * Task 023's global restart shortcut/action (`design/README.md` §4's Restart tooltip: "Force-stop,
 * then launch the main activity  ⇧⌘R"; `design/IMPLEMENTATION.md`'s example `plugin.xml` names this
 * exact action id, `AdbToolbox.RestartApp`). Forwards to the exact same
 * [dev.acme.adbtoolbox.application.apps.AppLifecycleViewModel.handle] entry point
 * [AppsPanel]'s Restart button uses (both reached via
 * [AdbToolboxProjectService.appLifecycleViewModel]), so the shortcut and the button can never
 * diverge into two different restart code paths — mirrors
 * [dev.acme.adbtoolbox.intellij.mirroring.MirroringToggleAction]'s established shape exactly.
 *
 * [actionPerformed] only forwards [AppLifecycleIntent.Restart] — [AppLifecycleViewModel.handle]
 * resolves the current device-eligible/selected-package target and enforces duplicate-in-flight
 * prevention itself (task 023's scope), so this class contains no ADB/package logic of its own and
 * never blocks the EDT it runs on.
 */
class RestartAppAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<AdbToolboxProjectService>().appLifecycleViewModel.handle(AppLifecycleIntent.Restart)
    }
}
