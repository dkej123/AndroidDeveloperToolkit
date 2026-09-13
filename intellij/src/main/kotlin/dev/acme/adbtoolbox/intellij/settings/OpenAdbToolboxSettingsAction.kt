package dev.acme.adbtoolbox.intellij.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

class OpenAdbToolboxSettingsAction internal constructor(
    private val project: Project,
    private val openSettings: (Project) -> Unit,
) : DumbAwareAction("Settings", "Open ADB Toolbox settings", AllIcons.General.GearPlain) {

    constructor(project: Project) : this(project, AdbToolboxSettingsOpener::open)

    override fun actionPerformed(event: AnActionEvent) {
        openSettings(project)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = !project.isDisposed
    }
}
