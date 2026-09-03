package dev.acme.adbtoolbox.application.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.selectedSerialOrNull
import dev.acme.adbtoolbox.domain.devicecontext.BadgeContributor
import dev.acme.adbtoolbox.domain.devicecontext.DeviceContextSnapshot
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.devicecontext.RunningProcessContributor
import dev.acme.adbtoolbox.domain.devicecontext.aggregateDeviceContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A handle returned by `register*Contributor`; call [unregister] when the feature that registered it is disposed. */
fun interface Registration {
    fun unregister()
}

/**
 * Combines whatever [BadgeContributor]/[RunningProcessContributor]/[OverrideSummaryContributor]s are
 * currently registered into one [DeviceContextSnapshot] per selected serial (task 014). Feature
 * packages own their own state and call `register*Contributor` from their own composition — nothing
 * here is a shared mutable map a feature writes into; [badgeContributors]/etc hold only *references*
 * to feature-owned contributors, and every snapshot is produced by [aggregateDeviceContext], a pure
 * read of whatever is registered *right now*. Recomputation happens on: the selected serial changing,
 * a contributor registering/unregistering, and [refresh] — a feature calls [refresh] after its own
 * contributor's internal state changes (e.g. a new unseen Logcat line), which is a deliberate pull
 * signal from the feature, not a value pushed into shared storage.
 *
 * [scope] follows the same feature-local-child-scope seam as
 * [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel] (ADR 0004); like that ViewModel,
 * teardown is owned by cancelling [scope] — there is no separate `dispose()` to forget to call.
 * Aggregation itself is synchronous (a pure in-memory combine, no I/O), so unlike other
 * `:application` ViewModels this needs no [dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider]
 * seam — there is no dispatcher choice to make.
 */
class DeviceContextAggregator(
    private val scope: CoroutineScope,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
) {
    private val badgeContributors = mutableListOf<BadgeContributor>()
    private val processContributors = mutableListOf<RunningProcessContributor>()
    private val overrideContributors = mutableListOf<OverrideSummaryContributor>()

    private var currentSerial: DeviceSerial? = selectedDeviceState.value.selectedSerialOrNull

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<DeviceContextSnapshot> = _state.asStateFlow()

    init {
        scope.launch {
            selectedDeviceState.map { it.selectedSerialOrNull }.distinctUntilChanged().collect { serial ->
                currentSerial = serial
                refresh()
            }
        }
    }

    fun registerBadgeContributor(contributor: BadgeContributor): Registration {
        badgeContributors += contributor
        refresh()
        return Registration { badgeContributors -= contributor; refresh() }
    }

    fun registerRunningProcessContributor(contributor: RunningProcessContributor): Registration {
        processContributors += contributor
        refresh()
        return Registration { processContributors -= contributor; refresh() }
    }

    fun registerOverrideSummaryContributor(contributor: OverrideSummaryContributor): Registration {
        overrideContributors += contributor
        refresh()
        return Registration { overrideContributors -= contributor; refresh() }
    }

    /** Recomputes [state] now, reading every registered contributor's *current* value for the current serial. */
    fun refresh() {
        _state.value = snapshot()
    }

    private fun snapshot() =
        aggregateDeviceContext(currentSerial, badgeContributors.toList(), processContributors.toList(), overrideContributors.toList())
}
