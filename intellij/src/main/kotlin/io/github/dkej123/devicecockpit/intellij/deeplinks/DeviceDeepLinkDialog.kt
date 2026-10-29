package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.application.deeplinks.*
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkCommands
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.packages.PackageEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Dispatchers.EDT without a modality element waits for nonModal(), i.e. until this modal dialog
 * closes; the dialog's own coroutines must resume while it is showing.
 */
internal fun deepLinkDialogScope(parent: CoroutineScope): CoroutineScope =
    CoroutineScope(parent.coroutineContext + ModalityState.any().asContextElement())

/** DialogWrapper retains IDE modality, disposal and keyboard handling; the whole surface follows the handoff. */
internal class DeviceDeepLinkDialog(
    project: Project,
    private val serial: DeviceSerial,
    deviceName: String,
    private val useCase: DeviceDeepLinkUseCase,
    parentScope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val connected: () -> Boolean,
    private val loadPackages: suspend () -> List<PackageEntry>,
    /** Puts text on the clipboard and shows [toast] when given. */
    private val copy: (text: String, toast: String?) -> Unit,
) : DialogWrapper(project, null, true, IdeModalityType.IDE, false) {
    private val scope = deepLinkDialogScope(parentScope)
    private val controller = DeviceDeepLinkController(CoroutineScope(scope.coroutineContext + dispatchers.io), useCase, serial)
    private val handlers = DeepLinkHandlersController(CoroutineScope(scope.coroutineContext + dispatchers.io), useCase, serial)
    private val form = DeviceDeepLinkPanel(serial, useCase, ::launchLink) { command -> copy(command, "Command copied") }
    private val surface = DeviceDeepLinkContent(form, serial, deviceName, ::clearForm, ::doCancelAction, ::doOKAction)
    private var packing = false

    init {
        title = "Open deep link"
        setUndecorated(true)
        Disposer.register(disposable, com.intellij.openapi.Disposable { scope.cancel() })
        init()
        rootPane.defaultButton = surface.openButton
        rootPane.registerKeyboardAction({ doCancelAction() }, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        rootPane.isOpaque = false
        fun transparentOutsideSurface(component: Component) {
            if (component === surface) return
            if (component is JPanel) component.isOpaque = false
            if (component is Container) component.components.forEach(::transparentOutsideSurface)
        }
        transparentOutsideSurface(rootPane.contentPane)
        (window as? JDialog)?.let(DeepLinkWindowSurface::attach)
        window.minimumSize = Dimension(JBUI.scale(480), JBUI.scale(220))
        form.uriField.addActionListener { doOKAction() }
        form.onLayoutChanged = ::packToContent
        // Editing URI, params or Open in invalidates the last result and re-checks who handles the link.
        form.onEdited = {
            form.showError(null)
            controller.dismiss()
            requestHandlers()
        }
        surface.onDismissResult = { controller.dismiss() }
        surface.onCopyOutput = { raw -> copy(raw, null) }
        surface.onResultAction = { action ->
            when (action) {
                is BannerAction.CopyVerifyCommand -> copy(action.command, action.toast)
                BannerAction.TurnOnColdStart -> surface.setColdStart(true)
            }
        }
        window.addWindowListener(object : java.awt.event.WindowAdapter() {
            // An app may have been installed meanwhile (design §2b).
            override fun windowActivated(event: java.awt.event.WindowEvent) { handlers.refresh(); requestHandlers() }
        })
        installHeaderDragging()
        scope.launch(dispatchers.main) {
            var lastOpened: DeviceLinkLaunchState? = null
            controller.state.collectLatest { state ->
                surface.setBusy(state.busy)
                val request = state.request
                val result = state.result
                // Launches started in the dialog show their result here, never as a toast (handoff 7 §5).
                surface.showResult(
                    if (result != null && request != null) LaunchBanner.of(result, serial, request.uri, request.packageName, state.coldStart) { form.appLabel(it) } else null,
                    state.atMillis, (result as? DeviceLinkResult.Opened)?.raw ?: (result as? DeviceLinkResult.Failed)?.raw.orEmpty(),
                )
                if (state.opened != null && state.atMillis != lastOpened?.atMillis) { lastOpened = state; form.refreshHistory() }
                packToContent()
            }
        }
        scope.launch(dispatchers.main) {
            handlers.state.collectLatest { state -> form.showHandlers(state); packToContent() }
        }
        requestHandlers()
        scope.launch(dispatchers.io) {
            try {
                val packages = loadPackages()
                withContext(dispatchers.main) { form.setPackages(packages); form.showHandlers(handlers.state.value); packToContent() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(dispatchers.main) { form.showError(error.message ?: "Could not load installed apps"); packToContent() }
            }
        }
    }
    override fun createContentPane(): JComponent = super.createContentPane().also {
        DeepLinkWindowSurface.install(rootPane, it)
    }
    override fun createCenterPanel(): JComponent = surface
    override fun createSouthPanel(): JComponent? = null
    override fun createContentPaneBorder() = JBUI.Borders.empty()
    override fun getPreferredFocusedComponent(): JComponent = form.uriField
    override fun doValidate(): ValidationInfo? = validation()?.let { ValidationInfo(it, form.uriField).withOKEnabled() }
    private fun validation(): String? = when {
        !connected() -> "Connect the selected device to open this link"
        DeepLinkCommands.isBrowserIntentUri(form.uriField.text.trim()) -> DeviceDeepLinkUseCase.INTENT_SCHEME_MESSAGE
        !DeepLinkCommands.isValidUri(form.uriField.text.trim()) -> "Enter a URI with a scheme, e.g. myapp://path or https://…"
        else -> null
    }
    override fun doOKAction() {
        if (controller.state.value.busy) return
        val message = validation()
        if (message != null) { form.showError(message); packToContent(); return }
        launchLink(form.uriField.text.trim(), form.selectedPackage)
    }
    private fun launchLink(uri: String, pkg: String) {
        if (!connected()) { form.showError("Connect the selected device to open this link"); return }
        controller.open(uri, pkg, form.coldStart)
    }

    private fun requestHandlers() = handlers.request(form.uriField.text, form.selectedPackage)
    private fun clearForm() {
        controller.clear()
        form.clearForm()
        surface.setBusy(false)
        form.uriField.requestFocusInWindow()
        packToContent()
    }
    private fun packToContent() {
        if (isDisposed) return
        // An open dialog is resized in the same event as the content change, so Swing paints one frame at
        // the new size. Deferred, it first painted the new layout clipped by the old window: a flicker.
        if (window.isShowing) { fitToContent(); return }
        if (packing) return
        packing = true
        SwingUtilities.invokeLater {
            try {
                if (!isDisposed) {
                    val screen = window.graphicsConfiguration?.bounds
                    fun fit(height: Int) = if (screen != null) height.coerceAtMost(screen.height - JBUI.scale(64)) else height
                    if (!window.isShowing) {
                        window.pack()
                        window.setSize(window.width, fit(window.height))
                    } else fitToContent()
                }
            } finally { packing = false }
        }
    }
    /**
     * An open dialog follows its content height in the same event as the change; width stays as the user
     * left it. Laid out first, so wrapped text (the result banner) is measured at its real width.
     */
    private fun fitToContent() {
        val screen = window.graphicsConfiguration?.bounds
        repeat(2) {
            window.validate()
            val wanted = window.preferredSize.height
            val height = if (screen != null) wanted.coerceAtMost(screen.height - JBUI.scale(64)) else wanted
            if (height == window.height) return
            window.setSize(window.width, height)
        }
        window.validate()
    }

    private fun installHeaderDragging() {
        val listener = object : MouseAdapter() {
            var offset: Point? = null
            override fun mousePressed(event: MouseEvent) { offset = Point(event.xOnScreen - window.x, event.yOnScreen - window.y) }
            override fun mouseDragged(event: MouseEvent) { offset?.let { window.setLocation(event.xOnScreen - it.x, event.yOnScreen - it.y) } }
        }
        surface.header.addMouseListener(listener)
        surface.header.addMouseMotionListener(listener)
        surface.header.components.forEach { it.addMouseListener(listener); it.addMouseMotionListener(listener) }
    }
}
