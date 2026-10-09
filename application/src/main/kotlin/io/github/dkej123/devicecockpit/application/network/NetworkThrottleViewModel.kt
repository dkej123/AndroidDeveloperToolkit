package io.github.dkej123.devicecockpit.application.network

import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.device.DeviceCommandContext
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.device.toCommandContext
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.network.NetworkThrottle
import io.github.dkej123.devicecockpit.domain.network.NetworkThrottleCommand
import io.github.dkej123.devicecockpit.domain.network.NetworkThrottleRead
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

/** Whether the selected device can be throttled at all. */
enum class ThrottleAvailability { NoDevice, PhysicalDevice, Emulator }

/**
 * The Network view's throttling section. [current] is always the emulator's own readback (a
 * preset, or `null` with [customDescription] for values set elsewhere), never the value last
 * clicked; [busy] covers a write plus its readback.
 */
data class NetworkThrottleViewState(
    val availability: ThrottleAvailability = ThrottleAvailability.NoDevice,
    val current: NetworkThrottle? = null,
    val customDescription: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface NetworkThrottleIntent {
    data class Apply(val throttle: NetworkThrottle) : NetworkThrottleIntent

    data object Refresh : NetworkThrottleIntent
}

/**
 * Applies emulator network-throttling presets ([NetworkThrottleCommand]) to the selected device and
 * reads the result back. Physical devices report [ThrottleAvailability.PhysicalDevice]: without root
 * Android offers no throttling command. A newer [NetworkThrottleIntent.Apply] cancels one still in
 * flight, like the quick toggles.
 */
class NetworkThrottleViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val transport: AdbTransport,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
) {
    private val _state = MutableStateFlow(NetworkThrottleViewState())
    val state: StateFlow<NetworkThrottleViewState> = _state.asStateFlow()

    private var serial: DeviceSerial? = null
    private val requests = MutableSharedFlow<NetworkThrottleIntent>(extraBufferCapacity = 1)

    init {
        scope.launch(dispatchers.io) {
            selectedDeviceState
                .map { (it.toCommandContext() as? DeviceCommandContext.Eligible)?.serial }
                .distinctUntilChanged()
                .collectLatest { eligible ->
                    serial = eligible
                    when {
                        eligible == null -> _state.value = NetworkThrottleViewState()
                        !NetworkThrottleCommand.isSupported(eligible) ->
                            _state.value = NetworkThrottleViewState(availability = ThrottleAvailability.PhysicalDevice)
                        else -> {
                            _state.value = NetworkThrottleViewState(availability = ThrottleAvailability.Emulator, busy = true)
                            readBack(eligible, error = null)
                        }
                    }
                }
        }
        scope.launch(dispatchers.io) { requests.collectLatest(::process) }
    }

    fun handle(intent: NetworkThrottleIntent) {
        requests.tryEmit(intent)
    }

    private suspend fun process(intent: NetworkThrottleIntent) {
        val target = serial?.takeIf(NetworkThrottleCommand::isSupported) ?: return
        when (intent) {
            NetworkThrottleIntent.Refresh -> readBack(target, error = null)
            is NetworkThrottleIntent.Apply -> {
                _state.update { it.copy(busy = true, error = null) }
                // Stop at the first refusal so a half-applied preset (speed without latency) is rare.
                var rejected: AdbTextResult? = null
                for (request in NetworkThrottleCommand.applyRequests(target, intent.throttle)) {
                    val result = transport.executeText(request)
                    if (!NetworkThrottleCommand.isAccepted(result)) {
                        rejected = result
                        break
                    }
                }
                val error = rejected?.let { "Emulator refused the throttle: ${it.stdout.trim().ifEmpty { it.outcome.toString() }}" }
                readBack(target, error)
            }
        }
    }

    private suspend fun readBack(target: DeviceSerial, error: String?) {
        val read = NetworkThrottleCommand.parseStatus(transport.executeText(NetworkThrottleCommand.statusRequest(target)))
        if (serial != target) return
        _state.update { current ->
            when (read) {
                is NetworkThrottleRead.Known -> current.copy(current = read.throttle, customDescription = null, busy = false, error = error)
                is NetworkThrottleRead.Custom -> current.copy(
                    current = null,
                    customDescription = "custom: ${read.downloadBitsPerSecond / 1000} kbit/s, ≤${read.maxLatencyMs} ms",
                    busy = false,
                    error = error,
                )
                is NetworkThrottleRead.Unreadable -> current.copy(busy = false, error = error ?: "Could not read the emulator's network status: ${read.reason}")
            }
        }
    }
}
