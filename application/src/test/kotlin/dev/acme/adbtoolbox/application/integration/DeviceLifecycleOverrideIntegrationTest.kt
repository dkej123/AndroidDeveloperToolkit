@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.integration

import dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator
import dev.acme.adbtoolbox.application.devicecontext.OverrideResetCoordinator
import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.application.display.density.DensityOverrideResetUseCase
import dev.acme.adbtoolbox.application.display.density.DensityUseCase
import dev.acme.adbtoolbox.application.display.density.DensityViewModel
import dev.acme.adbtoolbox.application.display.fontscale.FontScaleIntent
import dev.acme.adbtoolbox.application.display.fontscale.FontScaleViewModel
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.network.ProxyController
import dev.acme.adbtoolbox.application.network.ProxyIntent
import dev.acme.adbtoolbox.application.network.ProxyOverrideSummaryContributor
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.FakeDeviceRepository
import dev.acme.adbtoolbox.domain.device.FakeDeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.devicecontext.FakeOverrideReapplyPersistence
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.network.FakeHostNetworkInfo
import dev.acme.adbtoolbox.domain.network.FakeNetworkRecentsPersistence
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private fun AdbRequest.shellText(): String? =
    ((this as? AdbDeviceRequest)?.operation as? AdbOperation.Shell)?.command?.render()

/**
 * Reverses [dev.acme.adbtoolbox.domain.adb.quoted]'s single-quote shell escaping so a fake
 * transport can recover the raw value a `ShellToken.Value` token rendered — every value-bearing
 * command (font-scale/proxy writes) renders its last token as `'...'` with embedded `'` escaped as
 * `'\''`, never as a bare token the way `wm density`'s literal dpi does.
 */
private fun String.unquotedLastShellToken(): String =
    substringAfterLast(' ').removeSurrounding("'").replace("'\\''", "'")

/**
 * A single [AdbTransport] shared by real font-scale/density/proxy features, the same way one
 * [dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService.adbTransport] instance backs
 * every feature in production. State is tracked per serial so a device switch (or reconnect)
 * exercises genuinely independent per-serial device truth instead of one shared global value.
 */
private class DeviceStateFakeAdbTransport : AdbTransport {
    private val fontScale = mutableMapOf<DeviceSerial, Double>()
    private val densityOverride = mutableMapOf<DeviceSerial, Int?>()
    private val proxyValue = mutableMapOf<DeviceSerial, String>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        val serial = (request as AdbDeviceRequest).serial
        val text = request.shellText().orEmpty()
        val stdout = when {
            text == "settings get system font_scale" -> (fontScale[serial] ?: 1.0).toString()
            text.startsWith("settings put system font_scale") -> {
                fontScale[serial] = text.unquotedLastShellToken().toDouble()
                ""
            }
            text == "wm density" -> {
                val override = densityOverride[serial]
                if (override == null) "Physical density: 420" else "Physical density: 420\nOverride density: $override"
            }
            text == "wm density reset" -> {
                densityOverride[serial] = null
                ""
            }
            text.startsWith("wm density") -> {
                densityOverride[serial] = text.substringAfterLast(' ').toInt()
                ""
            }
            text == "settings get global http_proxy" -> proxyValue[serial] ?: ":0"
            text.startsWith("settings put global http_proxy") -> {
                proxyValue[serial] = text.unquotedLastShellToken()
                ""
            }
            else -> error("unexpected command: $text")
        }
        return AdbTextResult(AdbOutcome.Completed(0), stdout = stdout, stderr = "")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> =
        listOf<AdbStreamEvent>(AdbStreamEvent.Completed(AdbOutcome.Completed(0))).asFlow()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private fun onlineDevice(serial: DeviceSerial) = Device(serial = serial, state = DeviceConnectionState.Online)

/**
 * Cross-feature integration coverage for task 052: real [FontScaleViewModel], [DensityViewModel],
 * and [ProxyController] instances — the exact same feature classes
 * [dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService] wires in production — driven
 * through [SelectedDeviceViewModel] and reconciled by the real [OverrideResetCoordinator] and
 * [DeviceContextAggregator]. This exercises the actual cross-feature contract task 041 introduced
 * (each feature owns its own state; the coordinator only delegates), not just the coordinator in
 * isolation against scripted fakes the way `OverrideResetCoordinatorTest` already does.
 */
class DeviceLifecycleOverrideIntegrationTest {

    private class Harness {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = TestDispatcherProviderFixture(dispatcher)
        val transport = DeviceStateFakeAdbTransport()
        val deviceRepository = FakeDeviceRepository()
        val selectedDeviceViewModel = SelectedDeviceViewModel(
            scope = scope,
            dispatchers = dispatchers,
            deviceRepository = deviceRepository,
            persistence = FakeDeviceSelectionPersistence(),
        )
        val commandContext = selectedDeviceViewModel.state
            .map { it.toCommandContext() }
            .stateIn(scope, SharingStarted.Eagerly, selectedDeviceViewModel.state.value.toCommandContext())

        val fontScaleViewModel = FontScaleViewModel(scope, dispatchers, transport, commandContext)
        val densityOverrideTracker = dev.acme.adbtoolbox.application.display.density.DensityOverrideTracker()
        val densityUseCase = DensityUseCase(transport, densityOverrideTracker)
        val densityViewModel = DensityViewModel(scope, dispatchers, densityUseCase, commandContext)
        val proxyController = ProxyController(
            scope = scope,
            dispatchers = dispatchers,
            transport = transport,
            selectedDeviceState = selectedDeviceViewModel.state,
            hostNetworkInfo = FakeHostNetworkInfo(),
            recentsPersistence = FakeNetworkRecentsPersistence(),
        )

        val aggregator = DeviceContextAggregator(scope, selectedDeviceViewModel.state)
        val fontScaleRegistration = aggregator.registerOverrideSummaryContributor(fontScaleViewModel.overrideContributor)
        val densityRegistration = aggregator.registerOverrideSummaryContributor(densityOverrideTracker)
        val proxyRegistration = aggregator.registerOverrideSummaryContributor(ProxyOverrideSummaryContributor(proxyController))

        init {
            // Mirrors the :intellij DisplayCoordinator/NetworkCoordinator glue: production calls
            // aggregator.refresh() on every feature-state emission (task 014's pull-signal contract),
            // since DeviceContextAggregator itself never observes feature state to stay decoupled.
            fontScaleViewModel.state.onEach { aggregator.refresh() }.launchIn(scope)
            densityViewModel.state.onEach { aggregator.refresh() }.launchIn(scope)
            proxyController.state.onEach { aggregator.refresh() }.launchIn(scope)
        }

        val feedback = FeedbackViewModel(scope, dispatchers)
        val reapplyPersistence = FakeOverrideReapplyPersistence()
        val coordinator = OverrideResetCoordinator(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceViewModel.state,
            resetUseCases = listOf(
                fontScaleViewModel.overrideResetUseCase(),
                DensityOverrideResetUseCase(densityUseCase, densityOverrideTracker),
                proxyController,
            ),
            reapplyPersistence = reapplyPersistence,
            feedback = feedback,
        )

        /**
         * Lets [SelectedDeviceViewModel]'s async persistence restore resolve before issuing an
         * explicit `Select` (task 054: restore unconditionally overwrites an in-flight selection when
         * it resolves after one, so every scenario here must let restore settle first — the same
         * ordering [SelectedDeviceViewModelTest] relies on).
         */
        fun selectOnline(serial: DeviceSerial) {
            scope.runCurrent()
            deviceRepository.emit(listOf(onlineDevice(serial)))
            selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serial))
        }
    }

    @Test
    fun `applying overrides on the real font-scale, density, and proxy features is reflected in the aggregated override count`() {
        with(Harness()) {
            selectOnline(serialA)
            scope.runCurrent()

            fontScaleViewModel.handle(FontScaleIntent.Apply(1.3))
            densityViewModel.handle(dev.acme.adbtoolbox.application.display.density.DensityIntent.ApplyPreset(125))
            proxyController.handle(ProxyIntent.Enable("10.0.0.5", "8888"))
            scope.runCurrent()

            aggregator.state.value.overrides.map { it.id }.toSet() shouldBe setOf("font-scale", "density", "proxy")
        }
    }

    @Test
    fun `resetAll delegates to the real feature use cases and every override reads back to defaults`() {
        with(Harness()) {
            selectOnline(serialA)
            scope.runCurrent()
            fontScaleViewModel.handle(FontScaleIntent.Apply(1.3))
            densityViewModel.handle(dev.acme.adbtoolbox.application.display.density.DensityIntent.ApplyPreset(125))
            proxyController.handle(ProxyIntent.Enable("10.0.0.5", "8888"))
            scope.runCurrent()
            aggregator.state.value.overrides.size shouldBe 3

            coordinator.resetAll()
            scope.runCurrent()

            aggregator.state.value.overrides shouldBe emptyList()
        }
    }

    @Test
    fun `switching the selected device never applies an override to the wrong serial`() {
        with(Harness()) {
            selectOnline(serialA)
            scope.runCurrent()
            fontScaleViewModel.handle(FontScaleIntent.Apply(1.5))
            scope.runCurrent()

            deviceRepository.emit(listOf(onlineDevice(serialA), onlineDevice(serialB)))
            selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialB))
            scope.runCurrent()

            // serialB never had font-scale applied — the aggregator (scoped to the now-selected serial)
            // must show no override, proving serialA's override state never leaked across the switch.
            aggregator.state.value.overrides shouldBe emptyList()

            fontScaleViewModel.handle(FontScaleIntent.Apply(1.5))
            scope.runCurrent()
            selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
            scope.runCurrent()

            // serialA must still show its own override untouched by serialB's mutation above.
            aggregator.state.value.overrides.single().id shouldBe "font-scale"
        }
    }

    @Test
    fun `a disconnect captures applied overrides and reconnect offers a re-apply without mutating the device`() {
        with(Harness()) {
            selectOnline(serialA)
            scope.runCurrent()
            fontScaleViewModel.handle(FontScaleIntent.Apply(1.3))
            scope.runCurrent()

            deviceRepository.emit(emptyList())
            scope.runCurrent()

            coordinator.pendingReapply.value.keys shouldBe setOf(serialA)
            feedback.state.value.toasts shouldBe emptyList()

            deviceRepository.emit(listOf(onlineDevice(serialA)))
            scope.runCurrent()

            val toast = feedback.state.value.toasts.single()
            toast.text.contains("Re-apply") shouldBe true

            coordinator.acceptReapply(serialA)
            scope.runCurrent()

            aggregator.state.value.overrides.single().id shouldBe "font-scale"
            coordinator.pendingReapply.value shouldBe emptyMap()
        }
    }
}
