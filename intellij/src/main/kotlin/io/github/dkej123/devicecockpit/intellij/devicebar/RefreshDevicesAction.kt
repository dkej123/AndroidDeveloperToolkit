package io.github.dkej123.devicecockpit.intellij.devicebar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import io.github.dkej123.devicecockpit.application.devicebar.DeviceBarIntent
import io.github.dkej123.devicecockpit.intellij.composition.AdbToolboxProjectService

/**
 * Task 050's global "refresh devices" shortcut (`design/README.md` Interactions: "Suggested global
 * shortcuts: ... ⌘⇧D refresh devices"), closing the gap where
 * [io.github.dkej123.devicecockpit.intellij.devicebar.DeviceContextBarPanel]'s refresh button already advertised
 * "Refresh device list  ⌘⇧D" in its tooltip with no keymap entry behind it. Forwards
 * [DeviceBarIntent.Refresh] to the exact same [AdbToolboxProjectService.deviceBarViewModel] instance
 * the refresh icon button uses, mirroring [io.github.dkej123.devicecockpit.intellij.logcat.FocusLogcatAction]'s
 * established shape.
 */
class RefreshDevicesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<AdbToolboxProjectService>().deviceBarViewModel.handle(DeviceBarIntent.Refresh)
    }
}
