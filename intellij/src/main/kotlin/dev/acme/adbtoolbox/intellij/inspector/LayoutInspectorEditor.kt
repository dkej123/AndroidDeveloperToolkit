package dev.acme.adbtoolbox.intellij.inspector

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightVirtualFile
import dev.acme.adbtoolbox.application.layout.CaptureLayoutUseCase
import dev.acme.adbtoolbox.application.layout.LayoutInspectorViewModel
import dev.acme.adbtoolbox.application.currentapp.CurrentAppUseCase
import dev.acme.adbtoolbox.application.nav.NavigationIntent
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService
import dev.acme.adbtoolbox.intellij.icons.AdbToolboxIcons
import java.beans.PropertyChangeListener
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Icon
import javax.swing.JComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/** The file type of inspector tabs: not registered for any extension, only our light files use it. */
object LayoutCaptureFileType : FileType {
    override fun getName() = "Device Cockpit Layout Capture"
    override fun getDescription() = "Device Cockpit layout capture"
    override fun getDefaultExtension() = ""
    override fun getIcon(): Icon = AdbToolboxIcons.Actions.layoutInspector
    override fun isBinary() = true
    override fun isReadOnly() = true
}

/** One inspector tab: a file-less editor titled "Layout · Pixel 9 · 12:04:31" (design §9). */
class LayoutInspectorFile(title: String) : LightVirtualFile(title, LayoutCaptureFileType, "") {
    init {
        isWritable = false
    }
}

/** Opens inspector tabs and owns their view models (one per tab, re-capture replaces in place). */
@Service(Service.Level.PROJECT)
class LayoutInspectorService(private val project: Project) : Disposable {
    private val models = linkedMapOf<LayoutInspectorFile, LayoutInspectorViewModel>()
    private val statusJobs = mutableMapOf<LayoutInspectorFile, Job>()
    private val _status = MutableStateFlow<InspectorOpenStatus?>(null)

    /** The newest open tab, for Capture's "Inspector open" row (design §9). */
    val status: StateFlow<InspectorOpenStatus?> = _status.asStateFlow()

    fun open() {
        val composition = project.service<AdbToolboxProjectService>()
        val name = (composition.selectedDeviceViewModel.state.value as? dev.acme.adbtoolbox.domain.device.SelectedDeviceState.Online)
            ?.device?.displayName ?: "no device"
        val file = LayoutInspectorFile("Layout · $name · ${SimpleDateFormat("HH:mm:ss").format(Date())}")
        val transport = composition.adbTransport
        val scope = composition.childScope()
        models[file] = LayoutInspectorViewModel(
            scope = scope,
            dispatchers = composition.dispatcherProvider,
            capture = CaptureLayoutUseCase(transport),
            currentApp = CurrentAppUseCase(transport),
            selected = composition.selectedDeviceViewModel.state,
            deviceName = { serial -> composition.deviceRepository.devices.value.firstOrNull { it.serial == serial }?.displayName ?: serial.toString() },
        ).also { model ->
            statusJobs[file] = model.state.onEach { publishStatus() }.launchIn(scope)
            model.recapture()
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** Brings the newest inspector tab forward. */
    fun show() {
        models.keys.lastOrNull()?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    /** Re-captures into the newest inspector tab, in place. */
    fun recaptureLatest() {
        models.values.lastOrNull()?.recapture()
    }

    private fun publishStatus() {
        _status.value = models.values.lastOrNull()?.let { InspectorOpenStatus(it.state.value.capture?.capturedAtMillis) }
    }

    fun model(file: LayoutInspectorFile): LayoutInspectorViewModel? = models[file]

    fun closed(file: LayoutInspectorFile) {
        models.remove(file)
        statusJobs.remove(file)?.cancel()
        publishStatus()
    }

    override fun dispose() {
        statusJobs.values.forEach { it.cancel() }
        statusJobs.clear()
        models.clear()
        _status.value = null
    }
}

class LayoutInspectorEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is LayoutInspectorFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = LayoutInspectorEditor(project, file as LayoutInspectorFile)

    override fun getEditorTypeId() = "adb-toolbox-layout-inspector"

    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class LayoutInspectorEditor(private val project: Project, private val file: LayoutInspectorFile) : UserDataHolderBase(), FileEditor {
    private val service = project.service<LayoutInspectorService>()
    private val composition = project.service<AdbToolboxProjectService>()
    private val scope = CoroutineScope(composition.dispatcherProvider.main + SupervisorJob())
    private val model = service.model(file)

    private val panel = LayoutInspectorPanel(
        onRecapture = { model?.recapture() },
        onOpenQuickToggles = {
            ToolWindowManager.getInstance(project).getToolWindow("ADB Toolbox")?.show()
            composition.navigationViewModel.handle(NavigationIntent.Select(ViewId.Device))
        },
        chooseOverlayFile = {
            FileChooser.chooseFile(
                FileChooserDescriptor(true, false, false, false, false, false).withFileFilter { it.extension.equals("png", true) }
                    .withTitle("Design Overlay")
                    .withDescription("Drop a PNG exported from Figma here, or choose a file. 1× = dp size; 2× and 3× exports are scaled down."),
                project,
                null,
            )?.let { java.io.File(it.path) }
        },
    )

    init {
        model?.state?.onEach { state -> withContext(composition.dispatcherProvider.main) { panel.update(state) } }?.launchIn(scope)
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.canvasForTest
    override fun getName() = "Layout Inspector"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        scope.cancel()
        service.closed(file)
    }

    @Suppress("unused")
    private val key = Key.create<Unit>("adbtoolbox.inspector")
}

/** ⌥⇧⌘I and Capture → Inspect layout: capture the selected device into a new inspector tab (design §9). */
class InspectLayoutAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<LayoutInspectorService>()?.open()
    }
}
