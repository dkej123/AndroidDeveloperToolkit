package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService

/**
 * Registers the plugin's project ToolWindow (`plugin.xml`) and creates its neutral placeholder
 * content (task 007) — real feature views land in later tasks. Delegates all wiring to
 * [AdbToolboxProjectService]; this class only asks the platform for that service and hands its
 * `shellViewModel`/`dispatcherProvider` to [AdbToolboxToolWindowPanel].
 */
class AdbToolboxToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val composition = project.service<AdbToolboxProjectService>()
        val panel = AdbToolboxToolWindowPanel(
            viewModel = composition.shellViewModel,
            dispatchers = composition.dispatcherProvider,
            scope = composition.childScope(),
        )
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
