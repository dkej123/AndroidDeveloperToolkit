package io.github.dkej123.devicecockpit.intellij.display

import io.github.dkej123.devicecockpit.application.display.toggles.DeviceSettingToggle
import io.github.dkej123.devicecockpit.application.display.toggles.DeviceSettingTogglesIntent
import io.github.dkej123.devicecockpit.application.display.toggles.DeviceSettingTogglesViewModel
import io.github.dkej123.devicecockpit.application.display.toggles.DeviceSettingTogglesViewState
import io.github.dkej123.devicecockpit.domain.display.toggles.ScreenRotation

import com.intellij.openapi.Disposable
import io.github.dkej123.devicecockpit.application.devicecontext.DeviceContextAggregator
import io.github.dkej123.devicecockpit.application.display.DisplayBadgeContributor
import io.github.dkej123.devicecockpit.application.display.QuickTogglesIntent
import io.github.dkej123.devicecockpit.application.display.QuickTogglesViewModel
import io.github.dkej123.devicecockpit.application.display.QuickTogglesViewState
import io.github.dkej123.devicecockpit.application.display.density.DensityIntent
import io.github.dkej123.devicecockpit.application.display.density.DensityViewModel
import io.github.dkej123.devicecockpit.application.display.density.DensityViewState
import io.github.dkej123.devicecockpit.application.display.developer.DeveloperOptionsIntent
import io.github.dkej123.devicecockpit.application.display.developer.DeveloperOptionsViewModel
import io.github.dkej123.devicecockpit.application.display.developer.DeveloperOptionsViewState
import io.github.dkej123.devicecockpit.application.display.developer.DeveloperToggle
import io.github.dkej123.devicecockpit.application.display.fontscale.FontScaleIntent
import io.github.dkej123.devicecockpit.application.display.fontscale.FontScaleViewModel
import io.github.dkej123.devicecockpit.application.display.QuickToggleFieldState
import io.github.dkej123.devicecockpit.application.feedback.FeedbackIntent
import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.domain.devicecontext.OverrideSummaryContributor
import io.github.dkej123.devicecockpit.domain.display.fontscale.FontScaleState
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackMessage
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects task 026/027/028's font-scale, density, and quick-toggles view models to the display
 * sections, which live in the Device view (there is no separate Display view any more), following [io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsCoordinator]'s
 * established shape: [scope] is owned by the caller, every state is collected and marshaled onto
 * [dispatchers]' `main` context before touching Swing, and each `render*` method is
 * `internal`/non-suspend so it is directly unit-testable against constructed state values.
 *
 * Registers [densityOverrideTracker]/[fontScaleViewModel]'s own [OverrideSummaryContributor] and a
 * [DisplayBadgeContributor] built from both with [aggregator] (task 014) here, in this feature's own
 * file, rather than the composition root editing a shared list — [dispose] unregisters both so a
 * disposed coordinator never contributes stale badge/override state.
 */
class DisplayCoordinator(
    /** Where the display sections are mounted: the Device view's [io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsPanel.displaySlot]. */
    slot: javax.swing.JPanel,
    private val fontScaleViewModel: FontScaleViewModel,
    private val densityViewModel: DensityViewModel,
    private val quickTogglesViewModel: QuickTogglesViewModel,
    densityOverrideTracker: OverrideSummaryContributor,
    private val feedback: FeedbackViewModel,
    private val aggregator: DeviceContextAggregator,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    developerOptionsViewModel: DeveloperOptionsViewModel? = null,
    deviceSettingTogglesViewModel: DeviceSettingTogglesViewModel? = null,
    /** Airplane / Wi-Fi block themselves while adb runs over Wi-Fi (design §5.3). */
    selectedDevice: kotlinx.coroutines.flow.StateFlow<io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState>? = null,
) : Disposable {

    val panel: DisplayPanel = DisplayPanel(
        onApplyFontScale = { value -> fontScaleViewModel.handle(FontScaleIntent.Apply(value)) },
        onResetFontScale = { fontScaleViewModel.handle(FontScaleIntent.Reset) },
        onApplyDensityPreset = { percent -> densityViewModel.handle(DensityIntent.ApplyPreset(percent)) },
        onApplyCustomDensity = { dpi -> densityViewModel.handle(DensityIntent.ApplyCustom(dpi)) },
        onResetDensity = { densityViewModel.handle(DensityIntent.Reset) },
        onSetDarkTheme = { enabled -> quickTogglesViewModel.handle(QuickTogglesIntent.SetDarkTheme(enabled)) },
        onSetShowTouches = { enabled -> quickTogglesViewModel.handle(QuickTogglesIntent.SetShowTouches(enabled)) },
        onSetAnimationsOff = { off -> quickTogglesViewModel.handle(QuickTogglesIntent.SetAnimationsOff(off)) },
        onSetTalkBack = { enabled -> quickTogglesViewModel.handle(QuickTogglesIntent.SetTalkBack(enabled)) },
        onSetDeveloperToggle = { toggle, enabled ->
            developerOptionsViewModel?.handle(DeveloperOptionsIntent.SetToggle(toggle, enabled))
        },
        onSetProcessLimit = { limit -> developerOptionsViewModel?.handle(DeveloperOptionsIntent.SetProcessLimit(limit)) },
        onSetDeviceSettingToggle = { toggle, enabled ->
            deviceSettingTogglesViewModel?.handle(DeviceSettingTogglesIntent.SetToggle(toggle, enabled))
        },
        onSetRotation = { rotation -> deviceSettingTogglesViewModel?.handle(DeviceSettingTogglesIntent.SetRotation(rotation)) },
        embedded = true,
    ).also { slot.add(it, java.awt.BorderLayout.CENTER) }

    private val overrideRegistration = aggregator.registerOverrideSummaryContributor(densityOverrideTracker)
    private val fontScaleOverrideRegistration =
        aggregator.registerOverrideSummaryContributor(fontScaleViewModel.overrideContributor)
    private val badgeRegistration = aggregator.registerBadgeContributor(
        DisplayBadgeContributor(fontScaleViewModel.overrideContributor, densityOverrideTracker),
    )

    init {
        fontScaleViewModel.state
            .onEach { state -> withContext(dispatchers.main) { renderFontScale(state) } }
            .launchIn(scope)
        densityViewModel.state
            .onEach { state -> withContext(dispatchers.main) { renderDensity(state) } }
            .launchIn(scope)
        quickTogglesViewModel.state
            .onEach { state -> withContext(dispatchers.main) { renderQuickToggles(state) } }
            .launchIn(scope)
        developerOptionsViewModel?.state
            ?.onEach { state -> withContext(dispatchers.main) { renderDeveloperOptions(state) } }
            ?.launchIn(scope)
        deviceSettingTogglesViewModel?.state
            ?.onEach { state -> withContext(dispatchers.main) { renderDeviceSettingToggles(state) } }
            ?.launchIn(scope)
        selectedDevice
            ?.onEach { selected ->
                val overWifi = (selected as? io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState.Online)
                    ?.device?.connectionKind == io.github.dkej123.devicecockpit.domain.device.DeviceConnectionKind.Wifi
                withContext(dispatchers.main) { panel.setConnectedOverWifi(overWifi) }
            }
            ?.launchIn(scope)
    }

    /**
     * Production code always reaches this already marshaled onto [dispatchers]' `main` context. Like
     * [renderDeveloperOptions], a failure is announced only when it ends the user's own change; a
     * rotation change is confirmed with a toast (design §5.3).
     */
    internal fun renderDeviceSettingToggles(state: DeviceSettingTogglesViewState) {
        panel.update(state)
        state.toggles.forEach { (toggle, field) ->
            if (field is QuickToggleFieldState.Error && lastSettingFields[toggle] is QuickToggleFieldState.Applying) {
                postError("${panel.settingLabelFor(toggle)}: ${field.message}")
            }
        }
        val rotation = state.rotation
        if (lastRotation is QuickToggleFieldState.Applying) {
            when (rotation) {
                is QuickToggleFieldState.Error -> postError("Rotation: ${rotation.message}")
                is QuickToggleFieldState.Idle -> post(
                    if (rotation.value == ScreenRotation.Auto) "Auto-rotate on" else "Rotation locked to ${rotation.value.name.lowercase()}",
                    FeedbackSeverity.Success,
                )
                else -> Unit
            }
        }
        lastSettingFields = state.toggles
        lastRotation = rotation
    }

    private var lastSettingFields: Map<DeviceSettingToggle, QuickToggleFieldState<Boolean>> = emptyMap()
    private var lastRotation: QuickToggleFieldState<ScreenRotation> = QuickToggleFieldState.Loading

    /**
     * Production code always reaches this already marshaled onto [dispatchers]' `main` context.
     * [DeviceContextAggregator.refresh] is called here (task 014's "a feature calls refresh() after
     * its own contributor's internal state changes" pull signal) since every font-scale readback —
     * not just an [FontScaleState.Error] one — may change what [fontScaleViewModel]'s override
     * contributor and this view's [DisplayBadgeContributor] report next.
     */
    internal fun renderFontScale(state: FontScaleState) {
        panel.update(state)
        aggregator.refresh()
        if (state is FontScaleState.Error) postError(state.message)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context; see [renderFontScale]. */
    internal fun renderDensity(state: DensityViewState) {
        panel.update(state)
        aggregator.refresh()
        if (state is DensityViewState.Error) postError(state.message)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun renderQuickToggles(state: QuickTogglesViewState) {
        panel.update(state)
        // A TalkBack failure is usually actionable (no known TalkBack: set a command in Settings),
        // so it is announced once per distinct error rather than only living in the toggle state.
        val talkBackError = state.talkBack as? QuickToggleFieldState.Error
        if (talkBackError != null && talkBackError != lastTalkBackError) postError(talkBackError.message)
        lastTalkBackError = talkBackError
    }

    /**
     * Production code always reaches this already marshaled onto [dispatchers]' `main` context. A
     * failure is announced only when it ends the user's own change (Applying → Error); a failing read
     * on device selection — e.g. surface updates on a non-root phone — stays inline in the row.
     */
    internal fun renderDeveloperOptions(state: DeveloperOptionsViewState) {
        panel.update(state)
        state.toggles.forEach { (toggle, field) ->
            if (field is QuickToggleFieldState.Error && lastDeveloperFields[toggle] is QuickToggleFieldState.Applying) {
                postError("${panel.developerLabelFor(toggle)}: ${field.message}")
            }
        }
        val limit = state.processLimit
        if (limit is QuickToggleFieldState.Error && lastProcessLimit is QuickToggleFieldState.Applying) {
            postError("Background process limit: ${limit.message}")
        }
        lastDeveloperFields = state.toggles
        lastProcessLimit = limit
    }

    private var lastDeveloperFields: Map<DeveloperToggle, QuickToggleFieldState<Boolean>> = emptyMap()
    private var lastProcessLimit: QuickToggleFieldState<Int> = QuickToggleFieldState.Loading

    private var lastTalkBackError: QuickToggleFieldState.Error<Boolean>? = null

    private fun postError(message: String) = post(message, FeedbackSeverity.Error)

    private fun post(message: String, severity: FeedbackSeverity) {
        feedback.handle(
            FeedbackIntent.Post(
                FeedbackMessage(id = "display-${severity.name.lowercase()}-${System.nanoTime()}", text = message, severity = severity),
            ),
        )
    }

    override fun dispose() {
        badgeRegistration.unregister()
        fontScaleOverrideRegistration.unregister()
        overrideRegistration.unregister()
        scope.cancel()
    }
}
