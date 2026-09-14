package dev.acme.adbtoolbox.intellij.display

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator
import dev.acme.adbtoolbox.application.display.QuickTogglesViewModel
import dev.acme.adbtoolbox.application.display.density.DensityOverrideTracker
import dev.acme.adbtoolbox.application.display.density.DensityUseCase
import dev.acme.adbtoolbox.application.display.density.DensityViewModel
import dev.acme.adbtoolbox.application.display.density.DensityViewState
import dev.acme.adbtoolbox.application.display.fontscale.FontScaleViewModel
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.display.density.DensityReading
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

private val serialA = DeviceSerial.of("AAAA111")

/**
 * Connects task 026/027/028's font-scale, density, and quick-toggles view models to
 * [DisplayPanel] via [DisplayCoordinator], mirroring
 * [dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsCoordinatorTest]'s established shape:
 * [DisplayCoordinator.renderFontScale]/[DisplayCoordinator.renderDensity]/[DisplayCoordinator.renderQuickToggles]
 * are exercised directly against constructed state values, without a real coroutine round trip.
 * The badge/override registration tests below drive [DensityOverrideTracker.record] directly
 * (a plain, synchronous method) rather than waiting on a real view model's asynchronous device
 * readback, so they stay deterministic without a timing-based `delay`.
 */
class DisplayCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    private class Fixture(
        val scope: CoroutineScope,
        val dispatchers: DispatcherProvider,
        val fontScaleViewModel: FontScaleViewModel,
        val densityViewModel: DensityViewModel,
        val quickTogglesViewModel: QuickTogglesViewModel,
        val densityOverrideTracker: DensityOverrideTracker,
        val feedback: FeedbackViewModel,
        val aggregator: DeviceContextAggregator,
    )

    private fun fixture(
        transport: FakeAdbTransport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "1.0", "") }),
    ): Fixture {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(
            SelectedDeviceState.Online(Device(serialA, DeviceConnectionState.Online)),
        )
        val commandContext = selectedDeviceState
            .map { it.toCommandContext() }
            .stateIn(scope, SharingStarted.Eagerly, selectedDeviceState.value.toCommandContext())
        val densityOverrideTracker = DensityOverrideTracker()
        return Fixture(
            scope = scope,
            dispatchers = dispatchers,
            fontScaleViewModel = FontScaleViewModel(scope, dispatchers, transport, commandContext),
            densityViewModel = DensityViewModel(
                scope, dispatchers, DensityUseCase(transport, densityOverrideTracker), commandContext,
            ),
            quickTogglesViewModel = QuickTogglesViewModel(scope, dispatchers, transport, selectedDeviceState),
            densityOverrideTracker = densityOverrideTracker,
            feedback = FeedbackViewModel(scope, dispatchers),
            aggregator = DeviceContextAggregator(scope, selectedDeviceState),
        )
    }

    private fun coordinator(host: AdbToolboxHostPanel, fixture: Fixture): DisplayCoordinator = DisplayCoordinator(
        host = host,
        fontScaleViewModel = fixture.fontScaleViewModel,
        densityViewModel = fixture.densityViewModel,
        quickTogglesViewModel = fixture.quickTogglesViewModel,
        densityOverrideTracker = fixture.densityOverrideTracker,
        feedback = fixture.feedback,
        aggregator = fixture.aggregator,
        scope = fixture.scope,
        dispatchers = fixture.dispatchers,
    )

    fun `test construction registers the Display view into the host's active view host`() {
        val host = AdbToolboxHostPanel()
        val fixture = fixture()

        val coord = coordinator(host, fixture)

        assertTrue(host.activeViewHost.isRegistered(ViewId.Display.routeKey))
        assertSame(coord.panel, host.activeViewHost.componentFor(ViewId.Display.routeKey))
        coord.dispose()
    }

    fun `test rendering a FontScale Error state posts an error toast`() {
        val host = AdbToolboxHostPanel()
        val fixture = fixture()
        val coord = coordinator(host, fixture)

        coord.renderFontScale(FontScaleState.Error(current = 1.0, message = "device offline"))

        val toast = fixture.feedback.state.value.toasts.single()
        assertEquals("device offline", toast.text)
        assertEquals(FeedbackSeverity.Error, toast.severity)
        coord.dispose()
    }

    fun `test rendering a Density Error state posts an error toast`() {
        val host = AdbToolboxHostPanel()
        val fixture = fixture()
        val coord = coordinator(host, fixture)

        coord.renderDensity(DensityViewState.Error(reading = null, message = "device offline"))

        val toast = fixture.feedback.state.value.toasts.single()
        assertEquals("device offline", toast.text)
        coord.dispose()
    }

    fun `test rendering a non-error FontScale state posts no toast`() {
        val host = AdbToolboxHostPanel()
        val fixture = fixture()
        val coord = coordinator(host, fixture)

        coord.renderFontScale(FontScaleState.Idle(1.0))

        assertTrue(fixture.feedback.state.value.toasts.isEmpty())
        coord.dispose()
    }

    fun `test renderDensity refreshes the aggregator so a newly-overridden reading reaches the Display badge`() {
        val host = AdbToolboxHostPanel()
        val fixture = fixture()
        val coord = coordinator(host, fixture)
        assertEquals(NavigationBadge.None, fixture.aggregator.state.value.badges[ViewId.Display] ?: NavigationBadge.None)

        // A real DensityViewModel readback would call this same record() before emitting Idle;
        // renderDensity's aggregator.refresh() is what the badge/override chip depend on afterward.
        fixture.densityOverrideTracker.record(serialA, DensityReading(420, 480))
        coord.renderDensity(DensityViewState.Idle(DensityReading(420, 480)))

        assertEquals(NavigationBadge.Attention, fixture.aggregator.state.value.badges[ViewId.Display])
        assertTrue(fixture.aggregator.state.value.overrides.isNotEmpty())

        coord.dispose()
    }

    fun `test disposing the coordinator unregisters its contributors and cancels its scope`() {
        val host = AdbToolboxHostPanel()
        val fixture = fixture()
        val coord = coordinator(host, fixture)
        fixture.densityOverrideTracker.record(serialA, DensityReading(420, 480))
        coord.renderDensity(DensityViewState.Idle(DensityReading(420, 480)))
        assertEquals(NavigationBadge.Attention, fixture.aggregator.state.value.badges[ViewId.Display])

        coord.dispose()

        assertFalse(fixture.scope.isActive)
        assertNull(fixture.aggregator.state.value.badges[ViewId.Display])
        assertTrue(fixture.aggregator.state.value.overrides.isEmpty())
    }

    fun `test registering twice does not recreate the panel`() {
        val host = AdbToolboxHostPanel()
        val fixtureA = fixture()
        val fixtureB = fixture()
        val first = coordinator(host, fixtureA)

        val second = coordinator(host, fixtureB)

        assertSame(first.panel, second.panel)
        first.dispose()
        second.dispose()
    }
}
