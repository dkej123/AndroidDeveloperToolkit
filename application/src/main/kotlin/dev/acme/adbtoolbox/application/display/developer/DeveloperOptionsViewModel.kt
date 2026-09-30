package dev.acme.adbtoolbox.application.display.developer

import dev.acme.adbtoolbox.application.display.QuickToggleFieldState
import dev.acme.adbtoolbox.application.display.valueOrNull
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.display.DisplaySettingRead
import dev.acme.adbtoolbox.domain.display.developer.ActivityManagerDebugPort
import dev.acme.adbtoolbox.domain.display.developer.AlwaysFinishActivitiesCommand
import dev.acme.adbtoolbox.domain.display.developer.DevOptionsAction
import dev.acme.adbtoolbox.domain.display.developer.ShellToggleCommand
import dev.acme.adbtoolbox.domain.display.developer.ShowSurfaceUpdatesCommand
import dev.acme.adbtoolbox.domain.display.developer.ShowViewUpdatesCommand
import dev.acme.adbtoolbox.domain.display.developer.StayAwakeCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Developer-options switches shown in the Device view's Quick toggles section (stay awake, don't
 * keep activities, show view/surface updates) plus the background process limit — next to, but
 * independent of, [dev.acme.adbtoolbox.application.display.QuickTogglesViewModel].
 *
 * Follows the same rules: every value shown comes from a device readback, each control's writes are
 * serialized by its own `collectLatest` (a newer request cancels an in-flight one), and a readback
 * for a device that is no longer selected is dropped. A switch is written only when its readback
 * disagrees with the target, which keeps SurfaceFlinger's toggling "off" write from turning it back on.
 */
class DeveloperOptionsViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val transport: AdbTransport,
    private val activityManager: ActivityManagerDebugPort,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
) {
    private val _state = MutableStateFlow(DeveloperOptionsViewState())
    val state: StateFlow<DeveloperOptionsViewState> = _state.asStateFlow()

    private val context = MutableStateFlow(selectedDeviceState.value.toCommandContext())

    private val toggleRequests = DeveloperToggle.entries.associateWith { MutableSharedFlow<Boolean>(extraBufferCapacity = 1) }
    private val processLimitRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    init {
        scope.launch(dispatchers.io) {
            selectedDeviceState.map { it.toCommandContext() }.distinctUntilChanged().collect { next ->
                context.value = next
                _state.value = DeveloperOptionsViewState()
                if (next is DeviceCommandContext.Eligible) refreshAll(next.serial)
            }
        }
        toggleRequests.forEach { (toggle, requests) ->
            scope.launch(dispatchers.io) { requests.collectLatest { enabled -> applyToggle(toggle, enabled) } }
        }
        scope.launch(dispatchers.io) { processLimitRequests.collectLatest(::applyProcessLimit) }
    }

    fun handle(intent: DeveloperOptionsIntent) {
        when (intent) {
            is DeveloperOptionsIntent.SetToggle -> toggleRequests.getValue(intent.toggle).tryEmit(intent.enabled)
            is DeveloperOptionsIntent.SetProcessLimit -> processLimitRequests.tryEmit(intent.limit)
            DeveloperOptionsIntent.Refresh -> {
                val serial = eligibleSerial() ?: return
                scope.launch(dispatchers.io) { refreshAll(serial) }
            }
        }
    }

    private suspend fun refreshAll(serial: DeviceSerial) {
        for (toggle in DeveloperToggle.entries) {
            val read = read(serial, toggle)
            if (!isCurrent(serial)) return
            setToggle(toggle, read.toFieldState(QuickToggleFieldState.Loading))
        }
        val limit = activityManager.run(serial, DevOptionsAction.Read)
        if (!isCurrent(serial)) return
        _state.update { it.copy(processLimit = limit.toFieldState(QuickToggleFieldState.Loading, ::helperFailure)) }
    }

    private suspend fun applyToggle(toggle: DeveloperToggle, enabled: Boolean) {
        val serial = eligibleSerial() ?: return
        val previous = _state.value.toggles.getValue(toggle)
        setToggle(toggle, QuickToggleFieldState.Applying(enabled))
        val current = read(serial, toggle)
        if (!isCurrent(serial)) return
        if (current is DisplaySettingRead.Value && current.value == enabled) {
            setToggle(toggle, current.toFieldState(previous))
            return
        }
        val writeFailure = write(serial, toggle, enabled)
        if (!isCurrent(serial)) return
        if (writeFailure != null) {
            setToggle(toggle, QuickToggleFieldState.Error(writeFailure, previous.valueOrNull()))
            return
        }
        val readback = read(serial, toggle)
        if (!isCurrent(serial)) return
        setToggle(toggle, readback.toFieldState(previous))
    }

    private suspend fun applyProcessLimit(limit: Int) {
        val serial = eligibleSerial() ?: return
        val previous = _state.value.processLimit
        _state.update { it.copy(processLimit = QuickToggleFieldState.Applying(limit)) }
        val result = activityManager.run(serial, DevOptionsAction.SetProcessLimit(limit))
        if (!isCurrent(serial)) return
        _state.update { it.copy(processLimit = result.toFieldState(previous, ::helperFailure)) }
    }

    private suspend fun read(serial: DeviceSerial, toggle: DeveloperToggle): DisplaySettingRead<Boolean> =
        when (toggle) {
            DeveloperToggle.DontKeepActivities ->
                AlwaysFinishActivitiesCommand.parseRead(transport.executeText(AlwaysFinishActivitiesCommand.readRequest(serial)))
            else -> {
                val command = shellCommandFor(toggle)
                command.parseRead(transport.executeText(command.readRequest(serial)))
            }
        }

    /** Returns the failure message to report, or `null` when the write went through and a readback should follow. */
    private suspend fun write(serial: DeviceSerial, toggle: DeveloperToggle, enabled: Boolean): String? {
        if (toggle == DeveloperToggle.DontKeepActivities) {
            val result = activityManager.run(serial, DevOptionsAction.SetAlwaysFinish(enabled))
            return (result.toFieldState(QuickToggleFieldState.Loading, ::helperFailure) as? QuickToggleFieldState.Error)?.message
        }
        shellCommandFor(toggle).writeRequests(serial, enabled).forEach { transport.executeText(it) }
        return null
    }

    private fun shellCommandFor(toggle: DeveloperToggle): ShellToggleCommand = when (toggle) {
        DeveloperToggle.StayAwake -> StayAwakeCommand
        DeveloperToggle.ShowViewUpdates -> ShowViewUpdatesCommand
        DeveloperToggle.ShowSurfaceUpdates -> ShowSurfaceUpdatesCommand
        DeveloperToggle.DontKeepActivities -> error("written through the activity manager helper")
    }

    private fun setToggle(toggle: DeveloperToggle, value: QuickToggleFieldState<Boolean>) {
        _state.update { it.copy(toggles = it.toggles + (toggle to value)) }
    }

    private fun eligibleSerial(): DeviceSerial? = (context.value as? DeviceCommandContext.Eligible)?.serial

    private fun isCurrent(serial: DeviceSerial): Boolean = eligibleSerial() == serial
}

private fun helperFailure(malformed: DisplaySettingRead.Malformed): String = "Device helper failed: ${malformed.reason}"

private fun <T> DisplaySettingRead<T>.toFieldState(
    previous: QuickToggleFieldState<T>,
    describeMalformed: (DisplaySettingRead.Malformed) -> String = { "Unexpected device output: ${it.raw.trim()}" },
): QuickToggleFieldState<T> = when (this) {
    is DisplaySettingRead.Value -> QuickToggleFieldState.Idle(value)
    DisplaySettingRead.NotSet -> QuickToggleFieldState.Error("Not supported on this device", previous.valueOrNull())
    is DisplaySettingRead.PermissionDenied -> QuickToggleFieldState.Error(message.trim(), previous.valueOrNull())
    is DisplaySettingRead.Malformed -> QuickToggleFieldState.Error(describeMalformed(this), previous.valueOrNull())
    is DisplaySettingRead.TransportFailed -> QuickToggleFieldState.Error(outcome.describe(), previous.valueOrNull())
}

private fun AdbOutcome.describe(): String = when (this) {
    is AdbOutcome.Completed -> "No output"
    AdbOutcome.TimedOut -> "Timed out"
    AdbOutcome.Cancelled -> "Cancelled"
    is AdbOutcome.TransportFailure -> reason
    is AdbOutcome.Unsupported -> reason
}
