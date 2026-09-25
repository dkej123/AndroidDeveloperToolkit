package dev.acme.adbtoolbox.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/** Shared entry point for the rail and recovery actions that need to open plugin settings. */
object AdbToolboxSettingsOpener {
    /**
     * Selects the page by configurable class: `showSettingsDialog(project, String)` matches a page's
     * display name, not its id, and silently opens the default page when nothing matches.
     */
    fun open(
        project: Project,
        show: (Project, Class<out Configurable>) -> Unit = { target, configurable ->
            ShowSettingsUtil.getInstance().showSettingsDialog(target, configurable)
        },
    ) {
        show(project, AdbToolboxSettingsConfigurable::class.java)
    }
}
