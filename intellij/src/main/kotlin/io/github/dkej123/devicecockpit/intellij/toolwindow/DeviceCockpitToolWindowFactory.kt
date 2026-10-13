package io.github.dkej123.devicecockpit.intellij.toolwindow

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.dkej123.devicecockpit.intellij.composition.DeviceCockpitProjectService
import io.github.dkej123.devicecockpit.intellij.settings.DeviceCockpitSettingsOpener
import io.github.dkej123.devicecockpit.intellij.settings.OpenDeviceCockpitSettingsAction

/**
 * Registers the plugin's project ToolWindow (`plugin.xml`) and creates its neutral placeholder
 * content (task 007) — real feature views land in later tasks. Delegates all wiring to
 * [DeviceCockpitProjectService]; this class only asks the platform for that service and hands its
 * `shellViewModel`/`dispatcherProvider`/`navigationViewModel`/`feedbackViewModel` to
 * [DeviceCockpitToolWindowPanel].
 *
 * [DumbAware]: nothing in the tool window needs project indexes (it only talks to adb), so the
 * platform must not swap its content for the "waiting for indexing" placeholder.
 */
class DeviceCockpitToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val composition = project.service<DeviceCockpitProjectService>()
        composition.registerMcpTools()
        val panel = DeviceCockpitToolWindowPanel(
            deepLinkMemory = composition.deepLinkLaunchMemory,
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
            appDetailsViewModel = composition.appDetailsViewModel,
            proxyController = composition.proxyController,
            networkScope = composition.childScope(),
            logcatControlsController = composition.logcatControlsController,
            networkThrottleViewModel = composition.networkThrottleViewModel,
            logcatScope = composition.childScope(),
            logcatPackageChoices = composition::logcatPackageChoices,
            fontScaleViewModel = composition.fontScaleViewModel,
            densityViewModel = composition.densityViewModel,
            quickTogglesViewModel = composition.quickTogglesViewModel,
            developerOptionsViewModel = composition.developerOptionsViewModel,
            deviceSettingTogglesViewModel = composition.deviceSettingTogglesViewModel,
            localeViewModel = composition.localeViewModel,
            currentAppViewModel = composition.currentAppViewModel,
            confirmCurrentApp = io.github.dkej123.devicecockpit.intellij.currentapp.CurrentAppCoordinator.confirmWithAppsDialogs(project) {
                (composition.selectedDeviceViewModel.state.value as? io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState.Online)
                    ?.device?.displayName ?: "the device"
            },
            registerCurrentAppDetailsOpener = { opener -> composition.openCurrentAppDetails = opener },
            locationViewModel = composition.locationViewModel,
            selectedDeviceState = composition.selectedDeviceViewModel.state,
            densityOverrideTracker = composition.densityOverrideTracker,
            deviceContextAggregator = composition.deviceContextAggregator,
            displayScope = composition.childScope(),
            openSettings = { DeviceCockpitSettingsOpener.open(project) },
            mirroringOptionsViewModel = composition.mirroringOptionsViewModel,
            onResetOverrides = { composition.overrideResetCoordinator.resetAll() },
            diagnosticsLog = composition.diagnosticsLog,
            sectionMeta = composition.deviceSectionMetaViewModel.state,
            inspectorStatus = project.service<io.github.dkej123.devicecockpit.intellij.inspector.LayoutInspectorService>().status,
            onShowInspector = { project.service<io.github.dkej123.devicecockpit.intellij.inspector.LayoutInspectorService>().show() },
            onRecaptureInspector = { project.service<io.github.dkej123.devicecockpit.intellij.inspector.LayoutInspectorService>().recaptureLatest() },
        )
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(OpenDeviceCockpitSettingsAction(project)))
        toolWindow.stripeTitle = "Device Cockpit"
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            ToolWindowReturnListener(toolWindow.id) { composition.viewEnterRefresher.refreshCurrent() },
        )
        // Collect Diagnostics / Record Performance / Open Log Folder in the tool window's ⋮ menu.
        (ActionManager.getInstance().getAction(DIAGNOSTICS_GROUP_ID) as? ActionGroup)?.let { group ->
            (toolWindow as? ToolWindowEx)?.setAdditionalGearActions(DefaultActionGroup(group))
        }
    }

    companion object {
        /** The tool window id (plugin.xml), kept from the pre-rename "ADB Toolbox" name for compatibility: saved window layouts and
         *  keymaps refer to it. Only the stripe title says "Device Cockpit". */
        const val TOOL_WINDOW_ID = "ADB Toolbox"

        /** Action group id, kept from the pre-rename "ADB Toolbox" name for compatibility (user keymaps and toolbars refer to it). */
        private const val DIAGNOSTICS_GROUP_ID = "AdbToolbox.Diagnostics"
    }
}
