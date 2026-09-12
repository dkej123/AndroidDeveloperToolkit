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
 * `shellViewModel`/`dispatcherProvider`/`navigationViewModel`/`feedbackViewModel` to
 * [AdbToolboxToolWindowPanel].
 */
class AdbToolboxToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val composition = project.service<AdbToolboxProjectService>()
        val panel = AdbToolboxToolWindowPanel(
            viewModel = composition.shellViewModel,
            dispatchers = composition.dispatcherProvider,
            scope = composition.childScope(),
            navigationViewModel = composition.navigationViewModel,
            navigationScope = composition.childScope(),
            feedbackViewModel = composition.feedbackViewModel,
            feedbackScope = composition.childScope(),
            deviceFactsViewModel = composition.deviceFactsViewModel,
            deviceFactsScope = composition.childScope(),
            deviceBarViewModel = composition.deviceBarViewModel,
            deviceBarScope = composition.childScope(),
            captureViewModel = composition.captureViewModel,
            captureScope = composition.childScope(),
            deviceActionsViewModel = composition.deviceActionsViewModel,
            deviceActionsScope = composition.childScope(),
            mirroringViewModel = composition.mirroringViewModel,
            mirroringScope = composition.childScope(),
        )
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
