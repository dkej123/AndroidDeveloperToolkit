package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService
import dev.acme.adbtoolbox.intellij.settings.AdbToolboxSettingsOpener
import dev.acme.adbtoolbox.intellij.settings.OpenAdbToolboxSettingsAction
import dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringOptionsDialog

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
            recordingViewModel = composition.recordingViewModel,
            recordingScope = composition.childScope(),
            appsViewModel = composition.appsViewModel,
            appLifecycleViewModel = composition.appLifecycleViewModel,
            clearDataViewModel = composition.clearDataViewModel,
            uninstallViewModel = composition.uninstallViewModel,
            appsScope = composition.childScope(),
            proxyController = composition.proxyController,
            networkScope = composition.childScope(),
            logcatControlsController = composition.logcatControlsController,
            logcatScope = composition.childScope(),
            fontScaleViewModel = composition.fontScaleViewModel,
            densityViewModel = composition.densityViewModel,
            quickTogglesViewModel = composition.quickTogglesViewModel,
            densityOverrideTracker = composition.densityOverrideTracker,
            deviceContextAggregator = composition.deviceContextAggregator,
            displayScope = composition.childScope(),
            openSettings = { AdbToolboxSettingsOpener.open(project) },
            openMirroringOptions = { MirroringOptionsDialog(project).show() },
            onResetOverrides = { composition.overrideResetCoordinator.resetAll() },
            diagnosticsLog = composition.diagnosticsLog,
            sectionMeta = composition.deviceSectionMetaViewModel.state,
        )
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(OpenAdbToolboxSettingsAction(project)))
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            ToolWindowReturnListener(toolWindow.id) { composition.viewEnterRefresher.refreshCurrent() },
        )
        // Collect Diagnostics / Record Performance / Open Log Folder in the tool window's ⋮ menu.
        (ActionManager.getInstance().getAction(DIAGNOSTICS_GROUP_ID) as? ActionGroup)?.let { group ->
            (toolWindow as? ToolWindowEx)?.setAdditionalGearActions(DefaultActionGroup(group))
        }
    }

    private companion object {
        const val DIAGNOSTICS_GROUP_ID = "AdbToolbox.Diagnostics"
    }
}
