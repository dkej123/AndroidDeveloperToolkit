package io.github.dkej123.devicecockpit.application.layout

import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppUseCase
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.device.selectedSerialOrNull
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.foreground.ForegroundState
import io.github.dkej123.devicecockpit.domain.layout.AccessibilityAudit
import io.github.dkej123.devicecockpit.domain.layout.AccessibilityReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One frozen capture of the inspector (design §9): screenshot, hierarchy, where and when. */
data class InspectorCapture(
    val snapshot: LayoutSnapshot,
    val serial: DeviceSerial,
    val deviceName: String,
    val activity: String?,
    val capturedAtMillis: Long,
) {
    val audit: AccessibilityReport by lazy { AccessibilityAudit.audit(snapshot.hierarchy) }
}

/** The inspector tab's state (design §9 state pills). */
data class LayoutInspectorState(
    val capture: InspectorCapture? = null,
    val capturing: Boolean = false,
    val error: String? = null,
    /** The capture's device is no longer online: the capture stays readable, Re-capture is disabled. */
    val deviceGone: Boolean = false,
    val deviceOnline: Boolean = false,
    val deviceName: String? = null,
)

/**
 * One Layout Inspector editor tab (task 064, design §9): captures the selected device's screen and
 * hierarchy, keeps the capture when the device goes away, and re-captures in place.
 */
class LayoutInspectorViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val capture: CaptureLayoutUseCase,
    private val currentApp: CurrentAppUseCase,
    private val selected: StateFlow<SelectedDeviceState>,
    private val deviceName: (DeviceSerial) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LayoutInspectorState())
    val state: StateFlow<LayoutInspectorState> = _state.asStateFlow()

    init {
        scope.launch(dispatchers.default) {
            selected.collect { device ->
                val online = device is SelectedDeviceState.Online
                val serial = device.selectedSerialOrNull
                _state.update { state ->
                    state.copy(
                        deviceOnline = online,
                        deviceName = serial?.let(deviceName),
                        deviceGone = state.capture != null && !(online && serial == state.capture.serial),
                    )
                }
            }
        }
    }

    fun recapture() {
        val device = selected.value as? SelectedDeviceState.Online ?: return
        val serial = device.device.serial
        if (_state.value.capturing) return
        _state.update { it.copy(capturing = true, error = null) }
        scope.launch(dispatchers.io) {
            val result = capture.snapshot(serial)
            val activity = runCatching {
                when (val front = currentApp.read(serial).foreground) {
                    is ForegroundState.App -> "${front.packageName}/${front.activity}"
                    is ForegroundState.SystemUi -> "System UI"
                    is ForegroundState.Home -> "${front.launcherPackage} (home)"
                    else -> null
                }
            }.getOrNull()
            _state.update { state ->
                when (result) {
                    is LayoutCapture.Captured -> state.copy(
                        capture = InspectorCapture(result.value, serial, deviceName(serial), activity, now()),
                        capturing = false,
                        error = null,
                        deviceGone = false,
                    )
                    is LayoutCapture.Failed -> state.copy(capturing = false, error = result.reason)
                }
            }
        }
    }
}
