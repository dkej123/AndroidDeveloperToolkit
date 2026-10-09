package io.github.dkej123.devicecockpit.intellij.display


import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.devicecontext.DeviceContextAggregator
import io.github.dkej123.devicecockpit.application.display.QuickTogglesViewModel
import io.github.dkej123.devicecockpit.application.display.density.DensityOverrideTracker
import io.github.dkej123.devicecockpit.application.display.density.DensityUseCase
import io.github.dkej123.devicecockpit.application.display.density.DensityViewModel
import io.github.dkej123.devicecockpit.application.display.density.DensityViewState
import io.github.dkej123.devicecockpit.application.display.fontscale.FontScaleViewModel
import io.github.dkej123.devicecockpit.application.display.QuickToggleFieldState
import io.github.dkej123.devicecockpit.application.display.developer.DeveloperOptionsViewState
import io.github.dkej123.devicecockpit.application.display.developer.DeveloperToggle
import io.github.dkej123.devicecockpit.application.display.QuickTogglesViewState
import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.device.Device
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.device.toCommandContext
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.display.density.DensityReading
import io.github.dkej123.devicecockpit.domain.display.fontscale.FontScaleState
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackSeverity
import io.github.dkej123.devicecockpit.domain.nav.NavigationBadge
import io.github.dkej123.devicecockpit.domain.nav.ViewId
import io.github.dkej123.devicecockpit.intellij.host.AdbToolboxHostPanel
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
 * [io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsCoordinatorTest]'s established shape:
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
        // Like production: collector renders are queued on the EDT behind the test body, so a
        // direct render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
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

    private val slots = mutableMapOf<AdbToolboxHostPanel, javax.swing.JPanel>()

    private fun coordinator(host: AdbToolboxHostPanel, fixture: Fixture): DisplayCoordinator = DisplayCoordinator(
        slot = slots.getOrPut(host) { javax.swing.JPanel(java.awt.BorderLayout()) },
        fontScaleViewModel = fixture.fontScaleViewModel,
        densityViewModel = fixture.densityViewModel,
        quickTogglesViewModel = fixture.quickTogglesViewModel,
        densityOverrideTracker = fixture.densityOverrideTracker,
        feedback = fixture.feedback,
        aggregator = fixture.aggregator,
        scope = fixture.scope,
        dispatchers = fixture.dispatchers,
    )

    fun `test construction mounts the display sections into the given slot instead of a view of their own`() {
        val host = AdbToolboxHostPanel()
        val fixture = fixture()

        val coord = coordinator(host, fixture)

        assertSame(coord.panel, slots.getValue(host).components.single())
        assertFalse(host.activeViewHost.isRegistered("display"))
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

    fun `test a TalkBack error is announced once, not on every re-render`() {
        val host = AdbToolboxHostPanel()
        val fixture = fixture()
        val coord = coordinator(host, fixture)
        val error = QuickTogglesViewState(talkBack = QuickToggleFieldState.Error("No known TalkBack installed", lastKnown = false))

        coord.renderQuickToggles(error)
        coord.renderQuickToggles(error.copy(darkTheme = QuickToggleFieldState.Idle(true)))

        assertEquals(listOf("No known TalkBack installed"), fixture.feedback.state.value.toasts.map { it.text })
        coord.dispose()
    }

    fun `test a developer-option error is announced only when it answers the user's own change`() {
        val host = AdbToolboxHostPanel()
        val fixture = fixture()
        val coord = coordinator(host, fixture)
        val rootError = QuickToggleFieldState.Error<Boolean>("Needs adb root", null)
        fun state(field: QuickToggleFieldState<Boolean>) =
            DeveloperOptionsViewState(toggles = mapOf(DeveloperToggle.ShowSurfaceUpdates to field))

        // Selecting a device reads the value: a failing read is shown inline only.
        coord.renderDeveloperOptions(state(rootError))
        assertTrue(fixture.feedback.state.value.toasts.isEmpty())

        coord.renderDeveloperOptions(state(QuickToggleFieldState.Applying(true)))
        coord.renderDeveloperOptions(state(rootError))
        coord.renderDeveloperOptions(state(rootError))

        assertEquals(listOf("Show surface updates: Needs adb root"), fixture.feedback.state.value.toasts.map { it.text })
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
        assertEquals(NavigationBadge.None, fixture.aggregator.state.value.badges[ViewId.Device] ?: NavigationBadge.None)

        // A real DensityViewModel readback would call this same record() before emitting Idle;
        // renderDensity's aggregator.refresh() is what the badge/override chip depend on afterward.
        fixture.densityOverrideTracker.record(serialA, DensityReading(420, 480))
        coord.renderDensity(DensityViewState.Idle(DensityReading(420, 480)))

        assertEquals(NavigationBadge.Attention, fixture.aggregator.state.value.badges[ViewId.Device])
        assertTrue(fixture.aggregator.state.value.overrides.isNotEmpty())

        coord.dispose()
    }

    fun `test disposing the coordinator unregisters its contributors and cancels its scope`() {
        val host = AdbToolboxHostPanel()
        val fixture = fixture()
        val coord = coordinator(host, fixture)
        fixture.densityOverrideTracker.record(serialA, DensityReading(420, 480))
        coord.renderDensity(DensityViewState.Idle(DensityReading(420, 480)))
        assertEquals(NavigationBadge.Attention, fixture.aggregator.state.value.badges[ViewId.Device])

        coord.dispose()

        assertFalse(fixture.scope.isActive)
        assertNull(fixture.aggregator.state.value.badges[ViewId.Device])
        assertTrue(fixture.aggregator.state.value.overrides.isEmpty())
    }
}
