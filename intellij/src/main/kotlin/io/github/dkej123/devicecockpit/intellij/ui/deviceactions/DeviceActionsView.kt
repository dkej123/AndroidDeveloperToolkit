package io.github.dkej123.devicecockpit.intellij.ui.deviceactions

import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButton
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButtonStyle
import io.github.dkej123.devicecockpit.intellij.ui.common.FlexRowLayout
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBPanel
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsIntent
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsViewModel
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsViewState
import io.github.dkej123.devicecockpit.domain.devicecontext.ControlPolicy
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Task 016's minimal functional Device-view binding: `design/README.md` §3's Device-section
 * action row — secondary **Reboot**, **Open shell**, **Wake** — with no other chrome; task 044
 * owns final visual styling. Follows [io.github.dkej123.devicecockpit.intellij.ui.capture.CaptureView]'s
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
) : JBPanel<DeviceActionsView>(FlexRowLayout(AdbToolboxTheme.Spacing.s3)), Disposable {

    val rebootButton = DesignButton("Reboot", DesignButtonStyle.SECONDARY).apply {
        toolTipText = "Reboot the selected device"
        addActionListener { viewModel.handle(DeviceActionsIntent.Reboot) }
    }

    val openShellButton = DesignButton("Open shell", DesignButtonStyle.SECONDARY).apply {
        toolTipText = "Open an adb shell session in the IDE's Terminal"
        addActionListener { viewModel.handle(DeviceActionsIntent.OpenShell) }
    }

    val wakeButton = DesignButton("Wake", DesignButtonStyle.SECONDARY).apply {
        toolTipText = "Wake the selected device"
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
