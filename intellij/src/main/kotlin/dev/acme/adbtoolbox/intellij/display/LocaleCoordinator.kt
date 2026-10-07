package dev.acme.adbtoolbox.intellij.display

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator
import dev.acme.adbtoolbox.application.locale.LocaleIntent
import dev.acme.adbtoolbox.application.locale.LocaleViewModel
import dev.acme.adbtoolbox.application.locale.LocationIntent
import dev.acme.adbtoolbox.application.locale.LocationViewModel
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import java.awt.BorderLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Mounts Language & region and Location (design §3b, §3c) into the Device view's display sections
 * and renders their view models; the locale override joins the status-bar chip through [aggregator].
 */
class LocaleCoordinator(
    displayPanel: DisplayPanel,
    localeViewModel: LocaleViewModel,
    locationViewModel: LocationViewModel,
    selectedDevice: StateFlow<SelectedDeviceState>,
    private val aggregator: DeviceContextAggregator,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : Disposable {
    val localeSection = LocaleSection(
        onSearch = { localeViewModel.handle(LocaleIntent.Search(it)) },
        onApply = { localeViewModel.handle(LocaleIntent.Apply(it)) },
        onReset = { localeViewModel.handle(LocaleIntent.Reset) },
    ).also { displayPanel.localeSlot.add(it, BorderLayout.CENTER) }

    val locationSection = LocationSection(
        onPreset = { locationViewModel.handle(LocationIntent.Preset(it)) },
        onCustom = { lat, lon -> locationViewModel.handle(LocationIntent.Custom(lat, lon)) },
    ).also { displayPanel.locationSlot.add(it, BorderLayout.CENTER) }

    private val overrideRegistration = aggregator.registerOverrideSummaryContributor(localeViewModel.overrideContributor)

    init {
        combine(localeViewModel.state, selectedDevice) { state, device -> state to (device is SelectedDeviceState.Online) }
            .onEach { (state, online) ->
                withContext(dispatchers.main) {
                    localeSection.update(state, online)
                    aggregator.refresh()
                }
            }
            .launchIn(scope)
        combine(locationViewModel.state, selectedDevice) { state, device -> state to (device is SelectedDeviceState.Online) }
            .onEach { (state, online) -> withContext(dispatchers.main) { locationSection.update(state, online) } }
            .launchIn(scope)
    }

    override fun dispose() {
        overrideRegistration.unregister()
        scope.cancel()
    }
}
