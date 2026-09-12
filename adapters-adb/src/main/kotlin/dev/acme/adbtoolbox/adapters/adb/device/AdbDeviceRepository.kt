@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package dev.acme.adbtoolbox.adapters.adb.device

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceListParser
import dev.acme.adbtoolbox.domain.device.DeviceListRefresher
import dev.acme.adbtoolbox.domain.device.DeviceRepository
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val DEFAULT_POLL_INTERVAL = 3.seconds
private val DEFAULT_COALESCE_WINDOW = 250.milliseconds
private val DEVICES_LIST_ARGUMENTS = listOf("devices", "-l")

/**
 * The `:adapters-adb` [DeviceRepository] (task 008): unifies two refresh triggers into one
 * deduplicated [devices] stream, deriving the actual device list from a single source of truth —
 * a fresh `adb devices -l` parse via [DeviceListParser] — on every trigger, rather than trying to
 * reconcile two different data shapes:
 * - a periodic [pollInterval] ticker, always active, so discovery works even when no hotplug
 *   source is available (Android plugin absent, ADR 0005's binary-only fallback);
 * - an optional [changeSignals] pulse stream (e.g. [DdmlibDeviceChangeListenerSource]'s hotplug
 *   events) that lets a real connect/disconnect be reflected sooner than the next tick.
 *
 * Both triggers are merged and [debounce]d by [coalesceWindow] before every refresh
 * (`collectLatest`, so a fresh trigger cancels an in-flight poll rather than racing with it) —
 * ddmlib hotplug notifications fire multiple times per physical connect/disconnect, and this keeps
 * that noise from causing redundant `adb devices -l` calls (task 008: "coalesce noisy events
 * deterministically"). [binaryTransport] must support [AdbServerRequest] (the binary transport,
 * ADR 0005 — `DdmlibAdbTransport` does not).
 *
 * [scope] is owned by the caller, never created here (ADR 0004): the refresh loop this class
 * launches is a plain child coroutine of [scope], so cancelling [scope] tears the loop — and, if
 * [changeSignals] is a cold hotplug-listener flow, its underlying listener registration — down
 * with no separate disposal step needed.
 *
 * An outcome other than [AdbOutcome.Completed] with a zero (or absent) exit code — a timeout,
 * cancellation, transport failure, or non-zero exit — leaves [devices] at its previous value rather
 * than clearing it: a transient `adb devices` hiccup must not flash every device to "disconnected"
 * (adb-development: "handle ... malformed/unexpected output without crashing the caller").
 */
class AdbDeviceRepository(
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    private val binaryTransport: AdbTransport,
    changeSignals: Flow<Unit> = emptyFlow(),
    pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    coalesceWindow: Duration = DEFAULT_COALESCE_WINDOW,
) : DeviceRepository, DeviceListRefresher {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    override val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val manualRefreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        scope.launch(dispatchers.io) {
            merge(tickerFlow(pollInterval), changeSignals, manualRefreshRequests)
                .onStart { emit(Unit) }
                .debounce(coalesceWindow)
                .collectLatest { performRefresh() }
        }
    }

    /**
     * [DeviceListRefresher]'s manual "refresh now" trigger (task 011's device-bar refresh
     * control): merely feeds [manualRefreshRequests] into the same coalesced/debounced pipeline
     * every other refresh trigger goes through, rather than calling [performRefresh] directly, so
     * a manual refresh racing a hotplug/poll tick is coalesced exactly like any other burst.
     */
    override suspend fun refresh() {
        manualRefreshRequests.emit(Unit)
    }

    private suspend fun performRefresh() {
        val result = binaryTransport.executeText(AdbServerRequest(arguments = DEVICES_LIST_ARGUMENTS))
        val outcome = result.outcome
        if (outcome is AdbOutcome.Completed && (outcome.exitCode == null || outcome.exitCode == 0)) {
            _devices.value = DeviceListParser.parseDevices(result.stdout)
        }
    }

    private fun tickerFlow(interval: Duration): Flow<Unit> = flow {
        while (true) {
            delay(interval)
            emit(Unit)
        }
    }
}
