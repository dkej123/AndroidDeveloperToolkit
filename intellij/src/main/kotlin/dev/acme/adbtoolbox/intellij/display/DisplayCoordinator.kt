package dev.acme.adbtoolbox.intellij.display

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator
import dev.acme.adbtoolbox.application.display.DisplayBadgeContributor
import dev.acme.adbtoolbox.application.display.QuickTogglesIntent
import dev.acme.adbtoolbox.application.display.QuickTogglesViewModel
import dev.acme.adbtoolbox.application.display.QuickTogglesViewState
import dev.acme.adbtoolbox.application.display.density.DensityIntent
import dev.acme.adbtoolbox.application.display.density.DensityViewModel
import dev.acme.adbtoolbox.application.display.density.DensityViewState
import dev.acme.adbtoolbox.application.display.developer.DeveloperOptionsIntent
import dev.acme.adbtoolbox.application.display.developer.DeveloperOptionsViewModel
import dev.acme.adbtoolbox.application.display.developer.DeveloperOptionsViewState
import dev.acme.adbtoolbox.application.display.developer.DeveloperToggle
import dev.acme.adbtoolbox.application.display.fontscale.FontScaleIntent
import dev.acme.adbtoolbox.application.display.fontscale.FontScaleViewModel
import dev.acme.adbtoolbox.application.display.QuickToggleFieldState
import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects task 026/027/028's font-scale, density, and quick-toggles view models to the display
 * sections, which live in the Device view (there is no separate Display view any more), following [dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsCoordinator]'s
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
    /** Where the display sections are mounted: the Device view's [dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel.displaySlot]. */
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
    }

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

    private fun postError(message: String) {
        feedback.handle(
            FeedbackIntent.Post(
                FeedbackMessage(id = "display-error-${System.nanoTime()}", text = message, severity = FeedbackSeverity.Error),
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
