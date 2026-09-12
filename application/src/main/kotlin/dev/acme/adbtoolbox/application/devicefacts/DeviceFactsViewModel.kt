package dev.acme.adbtoolbox.application.devicefacts

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.devicefacts.ClipboardPort
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactsReportFormatter
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactsSnapshot
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the Device view's facts section (task 015, ADR 0004's MVI shape, mirroring
 * [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel]'s
 * feature-local-child-scope/dispatcher-injection seam). [selectedDeviceState] is the shared
 * [SelectedDeviceState] `StateFlow` (composed once, task 007/009) — this ViewModel never resolves
 * device selection itself, only reacts to it, per ADR 0009-adjacent "no feature re-derives shared
 * state" discipline already established by [dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator].
 *
 * **Stale-serial suppression / cancellation** (task 015's acceptance criteria: "stale-device
 * results never render"): the eligible-serial stream is collected with [collectLatest] — when the
 * selection changes to a different serial (or becomes ineligible), the previous serial's in-flight
 * [loadDeviceFacts] collection (and every child fetch coroutine [LoadDeviceFactsUseCase] launched
 * under it) is cancelled structurally before the new serial's fetch begins. This is the same
 * "cancel, don't just discard" guarantee ADR 0005 requires of streaming ADB calls, applied here to
 * this use case's per-fact fan-out: a slow response for a previously-selected device can only ever
 * be delivered from a coroutine that collectLatest has already torn down, so it can never reach
 * [_snapshot].
 */
class DeviceFactsViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val loadDeviceFacts: LoadDeviceFactsUseCase,
    private val clipboard: ClipboardPort,
) {
    private val _snapshot = MutableStateFlow<DeviceFactsSnapshot?>(null)

    private val _effects = Channel<DeviceFactsEffect>(Channel.BUFFERED)
    val effects: Flow<DeviceFactsEffect> = _effects.receiveAsFlow()

    val state: StateFlow<DeviceFactsViewState> =
        combine(selectedDeviceState, _snapshot) { selected, snapshot -> reduce(selected.toCommandContext(), snapshot) }
            .stateIn(scope, SharingStarted.Eagerly, reduce(selectedDeviceState.value.toCommandContext(), null))

    init {
        scope.launch(dispatchers.default) {
            selectedDeviceState
                .map { (it.toCommandContext() as? DeviceCommandContext.Eligible)?.serial }
                .distinctUntilChanged()
                .collectLatest { serial -> loadFacts(serial) }
        }
    }

    private suspend fun loadFacts(serial: DeviceSerial?) {
        if (serial == null) {
            _snapshot.value = null
            return
        }
        _snapshot.value = DeviceFactsSnapshot.loading(serial)
        loadDeviceFacts.execute(serial).collect { (factId, factState) ->
            val current = _snapshot.value
            if (current == null || current.serial != serial) return@collect
            _snapshot.value = current.copy(facts = current.facts + (factId to factState))
        }
    }

    fun handle(intent: DeviceFactsIntent) {
        when (intent) {
            DeviceFactsIntent.CopyReport -> copyReport()
        }
    }

    private fun copyReport() {
        val snapshot = _snapshot.value ?: return
        clipboard.writeText(DeviceFactsReportFormatter.format(snapshot))
        _effects.trySend(DeviceFactsEffect.ReportCopied)
    }

    private fun reduce(context: DeviceCommandContext, snapshot: DeviceFactsSnapshot?): DeviceFactsViewState = when (context) {
        is DeviceCommandContext.Disabled.SelectionError -> DeviceFactsViewState.RecoverableError(context.message)
        is DeviceCommandContext.Disabled.Loading -> DeviceFactsViewState.Loading
        is DeviceCommandContext.Disabled -> DeviceFactsViewState.NoDevice
        is DeviceCommandContext.Eligible -> when {
            snapshot == null || snapshot.serial != context.serial -> DeviceFactsViewState.Loading
            snapshot.isSettled -> DeviceFactsViewState.Connected(snapshot)
            else -> DeviceFactsViewState.Partial(snapshot)
        }
    }
}
