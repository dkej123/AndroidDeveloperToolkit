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
import dev.acme.adbtoolbox.domain.network.HostIpv4Result
import dev.acme.adbtoolbox.domain.network.HostNetworkInfo
import dev.acme.adbtoolbox.domain.network.NetworkRecentsPersistence
import dev.acme.adbtoolbox.domain.network.ProxyCommands
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyHost
import dev.acme.adbtoolbox.domain.network.ProxyHostResult
import dev.acme.adbtoolbox.domain.network.ProxyPort
import dev.acme.adbtoolbox.domain.network.ProxyPortResult
import dev.acme.adbtoolbox.domain.network.ProxyReadState
import dev.acme.adbtoolbox.domain.network.RecentProxyEndpoints
import dev.acme.adbtoolbox.domain.network.parseProxyReadback
import dev.acme.adbtoolbox.domain.network.resolveHostIpv4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private sealed interface ProxyWorkItem {
    val generation: Long

    data class Read(val serial: DeviceSerial, override val generation: Long) : ProxyWorkItem
    data class Enable(
        val serial: DeviceSerial,
        override val generation: Long,
        val endpoint: ProxyEndpoint,
    ) : ProxyWorkItem
    data class Reset(val serial: DeviceSerial, override val generation: Long) : ProxyWorkItem
    data class ResolveIp(override val generation: Long) : ProxyWorkItem
}

/**
 * Owns global-proxy state for the currently selected device (task 030), extended by task 032 with
 * live form editing, "Use my computer IP" resolution, and a persisted per-project MRU recents list:
 * reads on selection and on reconnect, applies enable/reset, and always reflects device-truth
 * readback — never the last requested value (the same principle tasks 026/027 use). All
 * device-touching work (reads, writes, and host-IP resolution alike) flows through one [workItems]
 * channel drained by a single consumer coroutine, so concurrent apply calls are serialized rather
 * than racing each other; every result is checked against [generation] before being applied, so a
 * result that lands after the selected device changed is discarded rather than clobbering the new
 * device's state (this also cancels a stale "Use my computer IP" lookup across a device switch).
 *
 * [hostInput]/[portInput]/[hostError]/[portError] track live form text independent of device state,
 * and are deliberately *not* reset when the selected device changes (`design/README.md`'s recents
 * are project-scoped, not per-serial) — only [ProxyViewState.readState]/[ProxyViewState.error]/
 * [ProxyViewState.isBusy] are, since those are genuinely per-serial truth.
 *
 * [recentsPersistence] is read once at construction and written to whenever an [ProxyIntent.Enable]
 * successfully reads back [ProxyReadState.Active] — never on every keystroke, and never for a
 * [ProxyIntent.SelectRecent] fill (which must never itself enable).
 *
 * [scope]/[dispatchers] follow the same feature-local-child-scope/dispatcher-injection seam as
 * [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel] (ADR 0004).
 */
class ProxyController(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val transport: AdbTransport,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val hostNetworkInfo: HostNetworkInfo,
    private val recentsPersistence: NetworkRecentsPersistence,
) {
    private val _state = MutableStateFlow(ProxyViewState())
    val state: StateFlow<ProxyViewState> = _state.asStateFlow()

    private val workItems = Channel<ProxyWorkItem>(Channel.UNLIMITED)

    private var currentSerial: DeviceSerial? = null
    private var currentContext: DeviceCommandContext = selectedDeviceState.value.toCommandContext()
    private var eligibleSinceLastRead = false
    private var generation = 0L

    init {
        scope.launch(dispatchers.io) {
            val recents = runCatching { recentsPersistence.readRecents() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(recents = recents)
        }
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
            is ProxyIntent.EditHost -> editHost(intent.text)
            is ProxyIntent.EditPort -> editPort(intent.text)
            is ProxyIntent.SelectRecent -> selectRecent(intent.endpoint)
            ProxyIntent.UseComputerIp -> useComputerIp()
        }
    }

    private fun onDeviceStateChanged(deviceState: SelectedDeviceState) {
        val serial = deviceState.selectedSerialOrNull
        if (serial != currentSerial) {
            currentSerial = serial
            eligibleSinceLastRead = false
            generation++
            // Only per-serial truth resets here — hostInput/portInput/recents are project-scoped
            // form/MRU state, not device state, and must survive a device switch untouched.
            _state.value = _state.value.copy(
                serial = serial,
                readState = null,
                isBusy = false,
                isResolvingIp = false,
                error = null,
            )
        }
        currentContext = deviceState.toCommandContext()
        when (val context = currentContext) {
            is DeviceCommandContext.Eligible -> {
                _state.value = _state.value.copy(isDeviceEligible = true)
                if (!eligibleSinceLastRead) {
                    eligibleSinceLastRead = true
                    enqueue(ProxyWorkItem.Read(context.serial, generation))
                }
            }
            is DeviceCommandContext.Disabled -> {
                eligibleSinceLastRead = false
                _state.value = _state.value.copy(isDeviceEligible = false)
            }
        }
    }

    private fun editHost(text: String) {
        val hostError = if (text.isBlank()) null else (ProxyHost.parse(text) as? ProxyHostResult.Invalid)?.reason
        _state.value = _state.value.copy(hostInput = text, hostError = hostError)
    }

    private fun editPort(text: String) {
        val portError = if (text.isBlank()) null else (ProxyPort.parse(text) as? ProxyPortResult.Invalid)?.reason
        _state.value = _state.value.copy(portInput = text, portError = portError)
    }

    private fun selectRecent(endpoint: ProxyEndpoint) {
        _state.value = _state.value.copy(
            hostInput = endpoint.host.value,
            portInput = endpoint.port.value.toString(),
            hostError = null,
            portError = null,
        )
    }

    private fun useComputerIp() {
        _state.value = _state.value.copy(isResolvingIp = true, error = null)
        enqueue(ProxyWorkItem.ResolveIp(generation))
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
        if (item !is ProxyWorkItem.ResolveIp) setBusy(item.generation, true)
        when (item) {
            is ProxyWorkItem.Read -> {
                val readResult = readProxy(item.serial)
                applyReadResult(item.generation, readResult)
            }
            is ProxyWorkItem.Enable -> {
                applyWrite(item.generation, item.serial, ProxyCommands.enable(item.endpoint))
                recordRecentIfActive(item.generation)
            }
            is ProxyWorkItem.Reset -> applyWrite(item.generation, item.serial, ProxyCommands.reset())
            is ProxyWorkItem.ResolveIp -> applyIpResolution(item.generation, hostNetworkInfo.resolveHostIpv4())
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

    private suspend fun recordRecentIfActive(generationAtDispatch: Long) {
        if (generationAtDispatch != generation) return
        val active = _state.value.readState as? ProxyReadState.Active ?: return
        val updated = RecentProxyEndpoints(_state.value.recents).withMostRecent(active.endpoint).endpoints
        _state.value = _state.value.copy(recents = updated)
        try {
            recentsPersistence.writeRecents(updated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (generationAtDispatch == generation) {
                val detail = failure.message?.takeIf { it.isNotBlank() } ?: "unknown error"
                _state.value = _state.value.copy(error = "Failed to save recent proxy endpoints: $detail")
            }
        }
    }

    private fun applyIpResolution(generationAtDispatch: Long, result: HostIpv4Result) {
        if (generationAtDispatch != generation) return
        _state.value = when (result) {
            is HostIpv4Result.Resolved -> _state.value.copy(
                hostInput = result.candidate.ipv4Address,
                hostError = null,
                isResolvingIp = false,
            )
            is HostIpv4Result.Ambiguous -> _state.value.copy(
                isResolvingIp = false,
                error = "Multiple network interfaces found — choose one manually",
            )
            HostIpv4Result.NotFound -> _state.value.copy(
                isResolvingIp = false,
                error = "No usable network interface found",
            )
            is HostIpv4Result.DiscoveryFailed -> _state.value.copy(isResolvingIp = false, error = result.reason)
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
