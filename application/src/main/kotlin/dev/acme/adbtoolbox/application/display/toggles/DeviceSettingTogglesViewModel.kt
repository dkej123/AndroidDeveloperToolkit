package dev.acme.adbtoolbox.application.display.toggles

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
import dev.acme.adbtoolbox.domain.display.toggles.AirplaneModeCommand
import dev.acme.adbtoolbox.domain.display.toggles.BoldTextCommand
import dev.acme.adbtoolbox.domain.display.toggles.DeviceSettingToggleCommand
import dev.acme.adbtoolbox.domain.display.toggles.GpuOverdrawCommand
import dev.acme.adbtoolbox.domain.display.toggles.GpuProfileBarsCommand
import dev.acme.adbtoolbox.domain.display.toggles.InvertColorsCommand
import dev.acme.adbtoolbox.domain.display.toggles.MobileDataCommand
import dev.acme.adbtoolbox.domain.display.toggles.PointerLocationCommand
import dev.acme.adbtoolbox.domain.display.toggles.ScreenRotation
import dev.acme.adbtoolbox.domain.display.toggles.ScreenRotationCommand
import dev.acme.adbtoolbox.domain.display.toggles.ShowLayoutBoundsCommand
import dev.acme.adbtoolbox.domain.display.toggles.WifiCommand
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

/** The on/off device settings added in task 059, in display order within their groups. */
enum class DeviceSettingToggle(val command: DeviceSettingToggleCommand) {
    ShowLayoutBounds(ShowLayoutBoundsCommand),
    GpuOverdraw(GpuOverdrawCommand),
    GpuProfileBars(GpuProfileBarsCommand),
    PointerLocation(PointerLocationCommand),
    BoldText(BoldTextCommand),
    InvertColors(InvertColorsCommand),
    AirplaneMode(AirplaneModeCommand),
    Wifi(WifiCommand),
    MobileData(MobileDataCommand),
}

data class DeviceSettingTogglesViewState(
    val toggles: Map<DeviceSettingToggle, QuickToggleFieldState<Boolean>> =
        DeviceSettingToggle.entries.associateWith { QuickToggleFieldState.Loading },
    val rotation: QuickToggleFieldState<ScreenRotation> = QuickToggleFieldState.Loading,
)

sealed interface DeviceSettingTogglesIntent {
    data class SetToggle(val toggle: DeviceSettingToggle, val enabled: Boolean) : DeviceSettingTogglesIntent

    data class SetRotation(val rotation: ScreenRotation) : DeviceSettingTogglesIntent

    data object Refresh : DeviceSettingTogglesIntent
}

/**
 * Rendering, accessibility and connectivity switches plus screen rotation (task 059, ported from Oh
 * My Android), with the quick-toggle rules of [dev.acme.adbtoolbox.application.display.developer.DeveloperOptionsViewModel]:
 * values come from device readbacks, each control's writes are serialized (`collectLatest`), a
 * switch is written only when the readback disagrees, and a readback for a device that is no longer
 * selected is dropped.
 */
class DeviceSettingTogglesViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val transport: AdbTransport,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
) {
    private val _state = MutableStateFlow(DeviceSettingTogglesViewState())
    val state: StateFlow<DeviceSettingTogglesViewState> = _state.asStateFlow()

    private val context = MutableStateFlow(selectedDeviceState.value.toCommandContext())
    private val toggleRequests = DeviceSettingToggle.entries.associateWith { MutableSharedFlow<Boolean>(extraBufferCapacity = 1) }
    private val rotationRequests = MutableSharedFlow<ScreenRotation>(extraBufferCapacity = 1)

    init {
        scope.launch(dispatchers.io) {
            selectedDeviceState.map { it.toCommandContext() }.distinctUntilChanged().collect { next ->
                context.value = next
                _state.value = DeviceSettingTogglesViewState()
                if (next is DeviceCommandContext.Eligible) refreshAll(next.serial)
            }
        }
        toggleRequests.forEach { (toggle, requests) ->
            scope.launch(dispatchers.io) { requests.collectLatest { enabled -> applyToggle(toggle, enabled) } }
        }
        scope.launch(dispatchers.io) { rotationRequests.collectLatest(::applyRotation) }
    }

    fun handle(intent: DeviceSettingTogglesIntent) {
        when (intent) {
            is DeviceSettingTogglesIntent.SetToggle -> toggleRequests.getValue(intent.toggle).tryEmit(intent.enabled)
            is DeviceSettingTogglesIntent.SetRotation -> rotationRequests.tryEmit(intent.rotation)
            DeviceSettingTogglesIntent.Refresh -> {
                val serial = eligibleSerial() ?: return
                scope.launch(dispatchers.io) { refreshAll(serial) }
            }
        }
    }

    private suspend fun refreshAll(serial: DeviceSerial) {
        for (toggle in DeviceSettingToggle.entries) {
            val read = read(serial, toggle)
            if (!isCurrent(serial)) return
            setToggle(toggle, read.toFieldState(QuickToggleFieldState.Loading))
        }
        val rotation = readRotation(serial)
        if (!isCurrent(serial)) return
        _state.update { it.copy(rotation = rotation.toFieldState(QuickToggleFieldState.Loading)) }
    }

    private suspend fun applyToggle(toggle: DeviceSettingToggle, enabled: Boolean) {
        val serial = eligibleSerial() ?: return
        val previous = _state.value.toggles.getValue(toggle)
        setToggle(toggle, QuickToggleFieldState.Applying(enabled))
        val current = read(serial, toggle)
        if (!isCurrent(serial)) return
        if (current !is DisplaySettingRead.Value || current.value != enabled) {
            toggle.command.writeRequests(serial, enabled).forEach { transport.executeText(it) }
            if (!isCurrent(serial)) return
            val readback = read(serial, toggle)
            if (!isCurrent(serial)) return
            setToggle(toggle, readback.toFieldState(previous))
        } else {
            setToggle(toggle, current.toFieldState(previous))
        }
    }

    private suspend fun applyRotation(rotation: ScreenRotation) {
        val serial = eligibleSerial() ?: return
        val previous = _state.value.rotation
        _state.update { it.copy(rotation = QuickToggleFieldState.Applying(rotation)) }
        ScreenRotationCommand.writeRequests(serial, rotation).forEach { transport.executeText(it) }
        if (!isCurrent(serial)) return
        val readback = readRotation(serial)
        if (!isCurrent(serial)) return
        _state.update { it.copy(rotation = readback.toFieldState(previous)) }
    }

    private suspend fun read(serial: DeviceSerial, toggle: DeviceSettingToggle): DisplaySettingRead<Boolean> =
        toggle.command.parseRead(transport.executeText(toggle.command.readRequest(serial)))

    private suspend fun readRotation(serial: DeviceSerial): DisplaySettingRead<ScreenRotation> =
        ScreenRotationCommand.parseRead(transport.executeText(ScreenRotationCommand.readRequest(serial)))

    private fun setToggle(toggle: DeviceSettingToggle, value: QuickToggleFieldState<Boolean>) {
        _state.update { it.copy(toggles = it.toggles + (toggle to value)) }
    }

    private fun eligibleSerial(): DeviceSerial? = (context.value as? DeviceCommandContext.Eligible)?.serial

    private fun isCurrent(serial: DeviceSerial): Boolean = eligibleSerial() == serial
}

private fun <T> DisplaySettingRead<T>.toFieldState(previous: QuickToggleFieldState<T>): QuickToggleFieldState<T> = when (this) {
    is DisplaySettingRead.Value -> QuickToggleFieldState.Idle(value)
    DisplaySettingRead.NotSet -> QuickToggleFieldState.Error("Not supported on this device", previous.valueOrNull())
    is DisplaySettingRead.PermissionDenied -> QuickToggleFieldState.Error(message.trim(), previous.valueOrNull())
    is DisplaySettingRead.Malformed -> QuickToggleFieldState.Error("Unexpected device output: ${raw.trim()}", previous.valueOrNull())
    is DisplaySettingRead.TransportFailed -> QuickToggleFieldState.Error(
        when (val o = outcome) {
            is AdbOutcome.Completed -> "No output"
            AdbOutcome.TimedOut -> "Timed out"
            AdbOutcome.Cancelled -> "Cancelled"
            is AdbOutcome.TransportFailure -> o.reason
            is AdbOutcome.Unsupported -> o.reason
        },
        previous.valueOrNull(),
    )
}
