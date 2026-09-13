package dev.acme.adbtoolbox.intellij.settings

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/** Shared entry point for the rail and recovery actions that need to open plugin settings. */
object AdbToolboxSettingsOpener {
    fun open(project: Project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AdbToolboxSettingsConfigurable.ID)
    }
}
