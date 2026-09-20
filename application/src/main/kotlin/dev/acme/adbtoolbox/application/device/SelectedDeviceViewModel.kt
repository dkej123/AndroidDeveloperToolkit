package dev.acme.adbtoolbox.application.device

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.DeviceRepository
import dev.acme.adbtoolbox.domain.device.DeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the one explicit selected-device context for a project (task 009), reduced from
 * [deviceRepository]'s live [DeviceRepository.devices] and a persisted/user-chosen [DeviceSerial]
 * (never an implicit first/only device — adb-development explicitly rejects that pattern).
 * [scope]/[dispatchers] follow the same feature-local-child-scope/dispatcher-injection seam as
 * [dev.acme.adbtoolbox.application.shell.ShellViewModel] (ADR 0004).
 *
 * [state] is a single `combine` of three sources — the repository's [StateFlow] (always the latest
 * device list, conflated), the currently intended serial, and whether persistence restore has
 * resolved — so every emission is recomputed from the *current* value of each input; there is no
 * per-emission `launch`/callback path that could let an older recomputation finish after (and
 * clobber) a newer one. The only place genuine out-of-order completion could occur is the
 * persistence *write* side effect triggered by [SelectedDeviceIntent.Select]/[SelectedDeviceIntent.ClearSelection]
 * (writes are real suspend I/O), which [collectLatest] below guards: a write still in flight when a
 * newer selection intent arrives is cancelled, so only the most recent selection is ever persisted.
 */
class SelectedDeviceViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val deviceRepository: DeviceRepository,
    private val persistence: DeviceSelectionPersistence,
) {
    private val _selectedSerial = MutableStateFlow<DeviceSerial?>(null)
    private val _restored = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    private val writeRequests = MutableSharedFlow<DeviceSerial?>(extraBufferCapacity = 1)

    @Volatile
    private var explicitSelectionHandled = false

    val state: StateFlow<SelectedDeviceState> =
        combine(deviceRepository.devices, _selectedSerial, _restored, _error, ::reduce)
            .stateIn(scope, SharingStarted.Eagerly, SelectedDeviceState.Loading)

    init {
        restore()
        scope.launch(dispatchers.io) {
            writeRequests.collectLatest { serial -> persistence.writeSelectedSerial(serial) }
        }
    }

    fun handle(intent: SelectedDeviceIntent) {
        when (intent) {
            is SelectedDeviceIntent.Select -> select(intent.serial)
            SelectedDeviceIntent.ClearSelection -> select(null)
            SelectedDeviceIntent.RetryRestore -> restore()
        }
    }

    private fun select(serial: DeviceSerial?) {
        explicitSelectionHandled = true
        _selectedSerial.value = serial
        writeRequests.tryEmit(serial)
    }

    private fun restore() {
        scope.launch(dispatchers.io) {
            runCatching { persistence.readSelectedSerial() }
                .onSuccess { persisted ->
                    _error.value = null
                    if (!explicitSelectionHandled) _selectedSerial.value = persisted
                    _restored.value = true
                }
                .onFailure { failure ->
                    _error.value = failure.message ?: failure::class.simpleName ?: "Unknown error"
                }
        }
    }

    private fun reduce(
        devices: List<Device>,
        serial: DeviceSerial?,
        restored: Boolean,
        error: String?,
    ): SelectedDeviceState = when {
        error != null -> SelectedDeviceState.Error(error)
        !restored -> SelectedDeviceState.Loading
        serial == null -> SelectedDeviceState.None
        else -> {
            val device = devices.find { it.serial == serial }
            if (device == null) {
                SelectedDeviceState.Disconnected(serial)
            } else {
                when (device.state) {
                    DeviceConnectionState.Online -> SelectedDeviceState.Online(device)
                    DeviceConnectionState.Unauthorized -> SelectedDeviceState.Unauthorized(device)
                    DeviceConnectionState.Offline -> SelectedDeviceState.Offline(device)
                    else -> SelectedDeviceState.Ineligible(device)
                }
            }
        }
    }
}
