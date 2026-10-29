package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.intellij.composition.DeviceCockpitProjectService

class OpenDeviceDeepLinkAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.service<DeviceCockpitProjectService>()
            ?.selectedDeviceViewModel?.state?.value is SelectedDeviceState.Online
    }
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<DeviceCockpitProjectService>()?.openDeviceDeepLink()
    }
}
