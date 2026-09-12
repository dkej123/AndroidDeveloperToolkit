package dev.acme.adbtoolbox.intellij.mirroring

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import dev.acme.adbtoolbox.application.mirroring.MirroringIntent
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService

/**
 * Task 018's global keyboard-shortcut binding (`design/README.md`'s suggested "shift meta M" —
 * `plugin.xml`'s `<keyboard-shortcut>` entries register the actual key combinations). Forwards to
 * the exact same [dev.acme.adbtoolbox.application.mirroring.MirroringViewModel.handle] entry point
 * [dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringView]'s button uses (both reached via
 * [AdbToolboxProjectService.mirroringViewModel]), so the shortcut and the button can never diverge
 * into two different start/stop code paths.
 *
 * [actionPerformed] only forwards [MirroringIntent.Toggle] — [MirroringViewModel.handle] resolves
 * start-vs-stop and enforces duplicate-start prevention itself (task 018's scope), so this class
 * contains no scrcpy/device logic of its own and never blocks the EDT it runs on.
 */
class MirroringToggleAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<AdbToolboxProjectService>().mirroringViewModel.handle(MirroringIntent.Toggle)
    }
}
