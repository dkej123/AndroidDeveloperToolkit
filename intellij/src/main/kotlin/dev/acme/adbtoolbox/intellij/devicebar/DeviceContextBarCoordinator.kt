package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.devicebar.DeviceBarIntent
import dev.acme.adbtoolbox.application.devicebar.DeviceBarViewModel
import dev.acme.adbtoolbox.application.devicebar.DeviceBarViewState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import java.awt.BorderLayout
import java.awt.Rectangle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects task 011's [DeviceBarViewModel] to task 010's [AdbToolboxHostPanel]:
 * [DeviceContextBarPanel] is mounted into [AdbToolboxHostPanel.deviceContextSlot], and
 * [DevicePickerListPanel] is shown as one bounded overlay in [AdbToolboxHostPanel.overlays] only
 * while the picker is open.
 *
 * Follows [dev.acme.adbtoolbox.intellij.feedback.FeedbackOverlayCoordinator]'s established shape:
 * [scope] is owned by the caller (never created here), state is collected and marshaled onto
 * [dispatchers]' `main` context before touching Swing, and [render] is `internal`/non-suspend so it
 * is directly unit-testable against constructed [DeviceBarViewState] values without depending on a
 * real coroutine round trip through this headless test sandbox.
 */
class DeviceContextBarCoordinator(
    host: AdbToolboxHostPanel,
    private val viewModel: DeviceBarViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : Disposable {

    val barPanel = DeviceContextBarPanel(
        onToggle = {
            val isOpen = viewModel.state.value.picker.isOpen
            viewModel.handle(if (isOpen) DeviceBarIntent.ClosePicker else DeviceBarIntent.OpenPicker)
        },
        onRefresh = { viewModel.handle(DeviceBarIntent.Refresh) },
    )

    private val pickerPanel = DevicePickerListPanel(
        onSelect = { serial -> viewModel.handle(DeviceBarIntent.SelectDevice(serial)) },
        onHighlightChange = { index -> viewModel.handle(DeviceBarIntent.HighlightAt(index)) },
        onConfirm = { viewModel.handle(DeviceBarIntent.ConfirmHighlighted) },
        onDismiss = { viewModel.handle(DeviceBarIntent.ClosePicker) },
        onPairOverWifi = { viewModel.handle(DeviceBarIntent.RequestPairOverWifi) },
    )

    private val overlays = host.overlays

    init {
        host.deviceContextSlot.add(barPanel, BorderLayout.CENTER)

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: DeviceBarViewState) {
        barPanel.update(state.bar)
        pickerPanel.update(state.picker)
        if (state.picker.isOpen) {
            overlays.show(pickerPanel, ::pickerBounds)
        } else {
            overlays.dismiss(pickerPanel)
        }
    }

    /**
     * `design/README.md` §1's device picker popup placement: "absolutely positioned
     * `top: 62, left: 8, right: 8`", height driven by the popup's own preferred (row-count-based)
     * height rather than a fixed value the design does not specify.
     */
    private fun pickerBounds(width: Int, height: Int): Rectangle {
        val insetWidth = (width - 16).coerceAtLeast(0)
        val top = 62
        val availableHeight = (height - top).coerceAtLeast(0)
        val preferredHeight = pickerPanel.preferredSize.height.coerceAtMost(availableHeight)
        return Rectangle(8, top, insetWidth, preferredHeight)
    }

    override fun dispose() {
        scope.cancel()
        overlays.dismiss(pickerPanel)
    }
}
