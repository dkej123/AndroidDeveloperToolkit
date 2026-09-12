package dev.acme.adbtoolbox.application.network

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.selectedSerialOrNull
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.network.ProxyCommands
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyHost
import dev.acme.adbtoolbox.domain.network.ProxyHostResult
import dev.acme.adbtoolbox.domain.network.ProxyPort
import dev.acme.adbtoolbox.domain.network.ProxyPortResult
import dev.acme.adbtoolbox.domain.network.parseProxyReadback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private sealed interface ProxyWorkItem {
    val serial: DeviceSerial
    val generation: Long

    data class Read(override val serial: DeviceSerial, override val generation: Long) : ProxyWorkItem
    data class Enable(
        override val serial: DeviceSerial,
        override val generation: Long,
        val endpoint: ProxyEndpoint,
    ) : ProxyWorkItem
    data class Reset(override val serial: DeviceSerial, override val generation: Long) : ProxyWorkItem
}

/**
 * Owns global-proxy state for the currently selected device (task 030): reads on selection and on
 * reconnect, applies enable/reset, and always reflects device-truth readback — never the last
 * requested value (the same principle tasks 026/027 use). All device-touching work (reads and
 * writes alike) flows through one [workItems] channel drained by a single consumer coroutine, so
 * concurrent apply calls are serialized rather than racing each other; every result is checked
 * against [generation] before being applied, so a result that lands after the selected device
 * changed is discarded rather than clobbering the new device's state.
 *
 * [scope]/[dispatchers] follow the same feature-local-child-scope/dispatcher-injection seam as
 * [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel] (ADR 0004).
 */
class ProxyController(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val transport: AdbTransport,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
) {
    private val _state = MutableStateFlow(ProxyViewState())
    val state: StateFlow<ProxyViewState> = _state.asStateFlow()

    private val workItems = Channel<ProxyWorkItem>(Channel.UNLIMITED)

    private var currentSerial: DeviceSerial? = null
    private var currentContext: DeviceCommandContext = selectedDeviceState.value.toCommandContext()
    private var eligibleSinceLastRead = false
    private var generation = 0L

    init {
        scope.launch {
            selectedDeviceState.collect { onDeviceStateChanged(it) }
        }
        scope.launch(dispatchers.io) {
            for (item in workItems) processItem(item)
        }
    }

    fun handle(intent: ProxyIntent) {
        when (intent) {
            is ProxyIntent.Enable -> enable(intent.hostInput, intent.portInput)
            ProxyIntent.Reset -> reset()
        }
    }

    private fun onDeviceStateChanged(deviceState: SelectedDeviceState) {
        val serial = deviceState.selectedSerialOrNull
        if (serial != currentSerial) {
            currentSerial = serial
            eligibleSinceLastRead = false
            generation++
            _state.value = ProxyViewState(serial = serial)
        }
        currentContext = deviceState.toCommandContext()
        when (val context = currentContext) {
            is DeviceCommandContext.Eligible -> {
                if (!eligibleSinceLastRead) {
                    eligibleSinceLastRead = true
                    enqueue(ProxyWorkItem.Read(context.serial, generation))
                }
            }
            is DeviceCommandContext.Disabled -> eligibleSinceLastRead = false
        }
    }

    private fun enable(hostInput: String, portInput: String) {
        val eligible = currentContext as? DeviceCommandContext.Eligible ?: run {
            _state.value = _state.value.copy(error = "No device selected")
            return
        }
        val hostResult = ProxyHost.parse(hostInput)
        val portResult = ProxyPort.parse(portInput)
        val invalidReason = ((hostResult as? ProxyHostResult.Invalid)?.reason)
            ?: (portResult as? ProxyPortResult.Invalid)?.reason
        if (invalidReason != null) {
            _state.value = _state.value.copy(error = invalidReason)
            return
        }
        val endpoint = ProxyEndpoint(
            (hostResult as ProxyHostResult.Valid).host,
            (portResult as ProxyPortResult.Valid).port,
        )
        enqueue(ProxyWorkItem.Enable(eligible.serial, generation, endpoint))
    }

    private fun reset() {
        val eligible = currentContext as? DeviceCommandContext.Eligible ?: run {
            _state.value = _state.value.copy(error = "No device selected")
            return
        }
        enqueue(ProxyWorkItem.Reset(eligible.serial, generation))
    }

    private fun enqueue(item: ProxyWorkItem) {
        workItems.trySend(item)
    }

    private suspend fun processItem(item: ProxyWorkItem) {
        if (item.generation != generation) return
        setBusy(item.generation, true)
        when (item) {
            is ProxyWorkItem.Read -> {
                val readResult = readProxy(item.serial)
                applyReadResult(item.generation, readResult)
            }
            is ProxyWorkItem.Enable -> applyWrite(item.generation, item.serial, ProxyCommands.enable(item.endpoint))
            is ProxyWorkItem.Reset -> applyWrite(item.generation, item.serial, ProxyCommands.reset())
        }
    }

    private suspend fun applyWrite(generationAtDispatch: Long, serial: DeviceSerial, command: AdbShellCommand) {
        val writeResult = transport.executeText(
            AdbDeviceRequest(serial, AdbOperation.Shell(command), timeout = COMMAND_TIMEOUT),
        )
        if (generationAtDispatch != generation) return
        if (writeResult.outcome !is AdbOutcome.Completed) {
            applyFailure(generationAtDispatch, writeResult.outcome)
            return
        }
        val readResult = readProxy(serial)
        applyReadResult(generationAtDispatch, readResult)
    }

    private suspend fun readProxy(serial: DeviceSerial): AdbTextResult =
        transport.executeText(AdbDeviceRequest(serial, AdbOperation.Shell(ProxyCommands.read()), timeout = COMMAND_TIMEOUT))

    private fun applyReadResult(generationAtDispatch: Long, result: AdbTextResult) {
        if (generationAtDispatch != generation) return
        when (result.outcome) {
            is AdbOutcome.Completed -> {
                val output = result.stdout.ifBlank { result.stderr }
                _state.value = _state.value.copy(readState = parseProxyReadback(output), isBusy = false, error = null)
            }
            else -> applyFailure(generationAtDispatch, result.outcome)
        }
    }

    private fun applyFailure(generationAtDispatch: Long, outcome: AdbOutcome) {
        if (generationAtDispatch != generation) return
        val message = when (outcome) {
            is AdbOutcome.TimedOut -> "Timed out"
            is AdbOutcome.Cancelled -> "Cancelled"
            is AdbOutcome.TransportFailure -> outcome.reason
            is AdbOutcome.Unsupported -> outcome.reason
            is AdbOutcome.Completed -> "Unexpected failure"
        }
        _state.value = _state.value.copy(isBusy = false, error = message)
    }

    private fun setBusy(generationAtDispatch: Long, busy: Boolean) {
        if (generationAtDispatch != generation) return
        _state.value = _state.value.copy(isBusy = busy)
    }

    private companion object {
        val COMMAND_TIMEOUT = 10.seconds
    }
}
