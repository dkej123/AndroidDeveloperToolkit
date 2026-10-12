package io.github.dkej123.devicecockpit.intellij.logcat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import io.github.dkej123.devicecockpit.application.nav.NavigationIntent
import io.github.dkej123.devicecockpit.domain.nav.ViewId
import io.github.dkej123.devicecockpit.intellij.composition.DeviceCockpitProjectService

/**
 * Task 037's global "focus Logcat" shortcut (`design/README.md` Interactions: "Suggested global
 * shortcuts: ... ⇧⌘L focus Logcat"). Forwards to the exact same
 * [io.github.dkej123.devicecockpit.application.nav.NavigationViewModel.handle] entry point the rail's own
 * Logcat destination uses (both reached via [DeviceCockpitProjectService.navigationViewModel]),
 * mirroring [io.github.dkej123.devicecockpit.intellij.apps.RestartAppAction]'s established shape — switching
 * views is this action's entire job; task 012's [io.github.dkej123.devicecockpit.intellij.nav.NavigationRoutingCoordinator]
 * owns actually showing the view.
 */
class FocusLogcatAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<DeviceCockpitProjectService>().navigationViewModel.handle(NavigationIntent.Select(ViewId.Logcat))
    }
}
