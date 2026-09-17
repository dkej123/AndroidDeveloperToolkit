package dev.acme.adbtoolbox.intellij.ui.deviceactions

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsIntent
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewModel
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Task 016's minimal functional Device-view binding: `design/README.md` §3's Device-section
 * action row — secondary **Reboot**, **Open shell**, **Wake** — with no other chrome; task 044
 * owns final visual styling. Follows [dev.acme.adbtoolbox.intellij.ui.capture.CaptureView]'s
 * established shape: [scope] is owned by the caller, state is collected and marshaled onto
 * [dispatchers]' `main` context before touching Swing, and [render] is `internal`/non-suspend for
 * direct unit testing.
 *
 * No button ever constructs an ADB/terminal command itself — every click only forwards a
 * [DeviceActionsIntent] to [viewModel] (task 016's acceptance criteria: "UI contains no command
 * construction").
 */
class DeviceActionsView(
    private val viewModel: DeviceActionsViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : JBPanel<DeviceActionsView>(FlowLayout(FlowLayout.LEFT, 4, 0)), Disposable {

    val rebootButton = JButton("Reboot").apply {
        toolTipText = "Reboot the selected device"
        preferredSize = Dimension(preferredSize.width, AdbToolboxTheme.Sizes.secondaryButton)
        addActionListener { viewModel.handle(DeviceActionsIntent.Reboot) }
    }

    val openShellButton = JButton("Open shell").apply {
        toolTipText = "Open an adb shell session in the IDE's Terminal"
        preferredSize = Dimension(preferredSize.width, AdbToolboxTheme.Sizes.secondaryButton)
        addActionListener { viewModel.handle(DeviceActionsIntent.OpenShell) }
    }

    val wakeButton = JButton("Wake").apply {
        toolTipText = "Wake the selected device"
        preferredSize = Dimension(preferredSize.width, AdbToolboxTheme.Sizes.secondaryButton)
        addActionListener { viewModel.handle(DeviceActionsIntent.Wake) }
    }

    init {
        isOpaque = false
        add(rebootButton)
        add(openShellButton)
        add(wakeButton)

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: DeviceActionsViewState) {
        val enabled = state.controlPolicy is ControlPolicy.Enabled && state.busyAction == null
        rebootButton.isEnabled = enabled
        openShellButton.isEnabled = enabled
        wakeButton.isEnabled = enabled
        if (state.controlPolicy is ControlPolicy.Enabled) {
            rebootButton.toolTipText = "Reboot the selected device"
            openShellButton.toolTipText = "Open an adb shell session in the IDE's Terminal"
            wakeButton.toolTipText = "Wake the selected device"
        } else {
            rebootButton.toolTipText = "Reboot the selected device — Connect a device to use this"
            openShellButton.toolTipText = "Open an adb shell session in the IDE's Terminal — Connect a device to use this"
            wakeButton.toolTipText = "Wake the selected device — Connect a device to use this"
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
