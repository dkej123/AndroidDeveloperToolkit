package io.github.dkej123.devicecockpit.application.deviceactions

import io.github.dkej123.devicecockpit.application.feedback.FeedbackIntent
import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.device.DeviceCommandContext
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.device.toCommandContext
import io.github.dkej123.devicecockpit.domain.devicecontext.controlPolicy
import io.github.dkej123.devicecockpit.domain.deviceactions.DeviceActionKind
import io.github.dkej123.devicecockpit.domain.deviceactions.DeviceActionResult
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackMessage
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * The feature ViewModel for task 016's minimal Device-view Reboot/Open-shell/Wake binding (ADR
 * 0004's MVI seam): a thin `:intellij` view renders [state] and forwards clicks to [handle].
 * [selectedDeviceState] drives [DeviceActionsViewState.controlPolicy] (task 014, mirrored from
 * [io.github.dkej123.devicecockpit.application.capture.CaptureViewModel]) and is read again — synchronously,
 * via `.value` — at the moment an intent is handled, so the [DeviceSerial] an action targets is
 * fixed for that action's whole lifetime.
 *
 * **Duplicate-in-flight prevention**: [busyAction] gates every intent, not just the one already
 * running — while any of the three actions is in flight, a second click (on the same or a
 * different button) is silently ignored rather than firing a second overlapping ADB/terminal call.
 *
 * [scope]/[dispatchers] follow the same feature-local-child-scope/dispatcher-injection seam as
 * every other ViewModel in this module (ADR 0004).
 */
class DeviceActionsViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val deviceActionsUseCase: DeviceActionsUseCase,
    private val openShellUseCase: OpenShellUseCase,
    private val feedback: FeedbackViewModel,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow(DeviceActionsViewState(controlPolicy = selectedDeviceState.value.controlPolicy()))
    val state: StateFlow<DeviceActionsViewState> = _state.asStateFlow()

    init {
        selectedDeviceState
            .onEach { current -> _state.update { it.copy(controlPolicy = current.controlPolicy()) } }
            .launchIn(scope)
    }

    fun handle(intent: DeviceActionsIntent) {
        when (intent) {
            DeviceActionsIntent.Reboot -> run(DeviceActionKind.Reboot) { serial -> deviceActionsUseCase.reboot(serial) }
            DeviceActionsIntent.Wake -> run(DeviceActionKind.Wake) { serial -> deviceActionsUseCase.wake(serial) }
            DeviceActionsIntent.OpenShell -> run(DeviceActionKind.OpenShell) { serial -> openShellUseCase.openShell(serial) }
        }
    }

    private fun run(kind: DeviceActionKind, action: suspend (DeviceSerial) -> DeviceActionResult) {
        if (_state.value.busyAction != null) return // duplicate in-flight request: silently ignored
        val context = selectedDeviceState.value.toCommandContext()
        if (context !is DeviceCommandContext.Eligible) {
            feedback.handle(
                FeedbackIntent.Post(
                    FeedbackMessage(
                        id = "device-action-no-device-${clock.now()}",
                        text = "No eligible device selected",
                        severity = FeedbackSeverity.Warning,
                    ),
                ),
            )
            return
        }
        val serial = context.serial
        _state.update { it.copy(busyAction = kind) }
        scope.launch {
            val result = withContext(dispatchers.io) { action(serial) }
            _state.update { it.copy(busyAction = null) }
            postResult(kind, result)
        }
    }

    private fun postResult(kind: DeviceActionKind, result: DeviceActionResult) {
        when (result) {
            DeviceActionResult.Success -> feedback.handle(
                FeedbackIntent.Post(
                    FeedbackMessage(
                        id = "device-action-success-$kind-${clock.now()}",
                        text = successText(kind),
                        severity = FeedbackSeverity.Success,
                    ),
                ),
            )
            is DeviceActionResult.Failure -> feedback.handle(
                FeedbackIntent.Post(
                    FeedbackMessage(
                        id = "device-action-error-$kind-${clock.now()}",
                        text = result.reason,
                        severity = FeedbackSeverity.Error,
                    ),
                ),
            )
        }
    }

    private fun successText(kind: DeviceActionKind): String = when (kind) {
        DeviceActionKind.Reboot -> "Rebooting device"
        DeviceActionKind.Wake -> "Woke device"
        DeviceActionKind.OpenShell -> "Opened shell"
    }
}
