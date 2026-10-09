package io.github.dkej123.devicecockpit.intellij.currentapp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppAction
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppViewModel
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Mounts Current app (design §3a) at the top of the Device view and feeds its freshness inputs:
 * shown/hidden (the section's own showing state covers both the rail and the tool window), IDE
 * focus, pointer and keyboard focus inside the section (a pointer leaving releases a held change
 * after 600 ms) and an open confirmation.
 */
class CurrentAppCoordinator(
    slot: JPanel,
    private val viewModel: CurrentAppViewModel,
    identity: (String) -> AppIdentity?,
    private val confirm: (CurrentAppAction, String) -> Boolean,
    onDetails: (String) -> Unit,
    onWake: () -> Unit,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : Disposable {
    val section = CurrentAppSection(
        identity = identity,
        onAction = { action, pkg -> act(action, pkg) },
        onDetails = onDetails,
        onRefresh = viewModel::refreshNow,
        onResetPermission = viewModel::resetPermission,
        onApplyPending = viewModel::applyPending,
        onWake = onWake,
        onLaunchLast = { pkg -> viewModel.perform(CurrentAppAction.Launch, pkg) },
    ).also { slot.add(it, java.awt.BorderLayout.CENTER) }

    private var pointerInside = false
    private val releasePointer = Timer(600) {
        pointerInside = false
        viewModel.setPointerInside(false)
    }.apply { isRepeats = false }

    private val mouseWatcher = AWTEventListener { event ->
        val mouse = event as? MouseEvent ?: return@AWTEventListener
        if (!section.isShowing || mouse.component == null) return@AWTEventListener
        val point = SwingUtilities.convertPoint(mouse.component, mouse.point, section)
        val inside = section.contains(point)
        if (inside) {
            releasePointer.stop()
            if (!pointerInside) {
                pointerInside = true
                viewModel.setPointerInside(true)
            }
        } else if (pointerInside && !releasePointer.isRunning) {
            releasePointer.restart()
        }
    }

    private val focusWatcher = java.beans.PropertyChangeListener { event ->
        val owner = event.newValue as? java.awt.Component
        viewModel.setFocusInside(owner != null && SwingUtilities.isDescendingFrom(owner, section))
    }

    // "updated 2 s ago" ages between readbacks.
    private val metaTicker = Timer(1000) { section.update(viewModel.state.value) }

    init {
        section.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                viewModel.setVisible(section.isShowing)
                if (section.isShowing) metaTicker.start() else metaTicker.stop()
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(mouseWatcher, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", focusWatcher)
        ApplicationManager.getApplication()?.messageBus?.connect(this)?.subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) = viewModel.setWindowFocused(true)

                override fun applicationDeactivated(ideFrame: IdeFrame) = viewModel.setWindowFocused(false)
            },
        )
        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { section.update(state) } }
            .launchIn(scope)
    }

    private fun act(action: CurrentAppAction, pkg: String) {
        if (action == CurrentAppAction.ClearData || action == CurrentAppAction.Uninstall) {
            viewModel.setConfirmOpen(true)
            val confirmed = try {
                confirm(action, pkg)
            } finally {
                viewModel.setConfirmOpen(false)
            }
            if (!confirmed) return
        }
        viewModel.perform(action, pkg)
    }

    override fun dispose() {
        metaTicker.stop()
        releasePointer.stop()
        Toolkit.getDefaultToolkit().removeAWTEventListener(mouseWatcher)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner", focusWatcher)
        scope.cancel()
    }

    companion object {
        /** The tool window's own Clear data / Uninstall confirmations, copy unchanged (design §3a). */
        fun confirmWithAppsDialogs(project: Project, deviceLabel: () -> String): (CurrentAppAction, String) -> Boolean = { action, pkg ->
            val (title, body, ok) = when (action) {
                CurrentAppAction.ClearData -> io.github.dkej123.devicecockpit.intellij.apps.clearDataDialogSpec(pkg, deviceLabel()).let { Triple(it.title, it.message, "Clear data") }
                else -> io.github.dkej123.devicecockpit.intellij.apps.uninstallDialogSpec(pkg, deviceLabel()).let { Triple(it.title, it.message, "Uninstall") }
            }
            io.github.dkej123.devicecockpit.intellij.apps.AppsConfirmationDialog(project, title, body, ok).showAndGet()
        }
    }
}

/** ⌥⇧⌘R (design §3a): restart the app in front, or launch it when Current app shows it as killed. */
class RestartForegroundAppAction : com.intellij.openapi.actionSystem.AnAction() {
    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT

    override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val project = e.project ?: return
        project.getService(io.github.dkej123.devicecockpit.intellij.composition.AdbToolboxProjectService::class.java)
            .currentAppViewModel.restartOrLaunchForeground()
    }
}
