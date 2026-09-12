@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.display

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
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.display.AnimationsSummary
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: kotlinx.coroutines.CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private fun onlineState(serial: DeviceSerial) =
    SelectedDeviceState.Online(Device(serial = serial, state = DeviceConnectionState.Online))

/** A per-device in-memory model of the three settings this feature reads/writes. */
private class FakeDeviceSettings(
    var darkTheme: Boolean = false,
    var showTouches: Boolean = false,
    var window: Float = 1f,
    var transition: Float = 1f,
    var animatorDuration: Float = 1f,
)

/**
 * A scripted [AdbTransport] double built for this test file: routes by (serial, rendered shell
 * command) into a per-serial [FakeDeviceSettings], with optional per-(serial, command) response
 * overrides and artificial [delay]s so tests can force deterministic interleaving without any real
 * ADB process or device (adb-development: fake ADB only in fast tests).
 */
private class ScriptedAdbTransport(
    private val settingsBySerial: Map<DeviceSerial, FakeDeviceSettings>,
) : AdbTransport {
    val requests = mutableListOf<AdbRequest>()
    val delays = mutableMapOf<Pair<DeviceSerial, String>, Long>()
    val overrides = mutableMapOf<Pair<DeviceSerial, String>, AdbTextResult>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        requests += request
        val deviceRequest = request as AdbDeviceRequest
        val rendered = (deviceRequest.operation as AdbOperation.Shell).command.render()
        val key = deviceRequest.serial to rendered
        delays[key]?.let { delay(it) }
        overrides[key]?.let { return it }
        return handle(deviceRequest.serial, rendered)
    }

    private fun handle(serial: DeviceSerial, rendered: String): AdbTextResult {
        val settings = settingsBySerial.getValue(serial)
        return when (rendered) {
            "cmd uimode night" -> ok(if (settings.darkTheme) "Night mode: yes" else "Night mode: no")
            "cmd uimode night yes" -> { settings.darkTheme = true; ok("") }
            "cmd uimode night no" -> { settings.darkTheme = false; ok("") }
            "settings get system show_touches" -> ok(if (settings.showTouches) "1" else "0")
            "settings put system show_touches 1" -> { settings.showTouches = true; ok("") }
            "settings put system show_touches 0" -> { settings.showTouches = false; ok("") }
            "settings get global window_animation_scale" -> ok(settings.window.toString())
            "settings get global transition_animation_scale" -> ok(settings.transition.toString())
            "settings get global animator_duration_scale" -> ok(settings.animatorDuration.toString())
            "settings put global window_animation_scale 1" -> { settings.window = 1f; ok("") }
            "settings put global window_animation_scale 0" -> { settings.window = 0f; ok("") }
            "settings put global transition_animation_scale 1" -> { settings.transition = 1f; ok("") }
            "settings put global transition_animation_scale 0" -> { settings.transition = 0f; ok("") }
            "settings put global animator_duration_scale 1" -> { settings.animatorDuration = 1f; ok("") }
            "settings put global animator_duration_scale 0" -> { settings.animatorDuration = 0f; ok("") }
            else -> error("unscripted command: $rendered")
        }
    }

    private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(exitCode = 0), stdout = stdout, stderr = "")

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = throw UnsupportedOperationException()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome =
        throw UnsupportedOperationException()
}

class QuickTogglesViewModelTest {

    private fun harness(
        settingsBySerial: Map<DeviceSerial, FakeDeviceSettings> = mapOf(serialA to FakeDeviceSettings()),
        initial: SelectedDeviceState = onlineState(serialA),
    ): Quintuple {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val transport = ScriptedAdbTransport(settingsBySerial)
        val selectedDeviceState = MutableStateFlow(initial)
        val viewModel = QuickTogglesViewModel(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            transport = transport,
            selectedDeviceState = selectedDeviceState,
        )
        return Quintuple(scope, transport, selectedDeviceState, viewModel, settingsBySerial)
    }

    private data class Quintuple(
        val scope: TestScope,
        val transport: ScriptedAdbTransport,
        val selectedDeviceState: MutableStateFlow<SelectedDeviceState>,
        val viewModel: QuickTogglesViewModel,
        val settingsBySerial: Map<DeviceSerial, FakeDeviceSettings>,
    )

    @Test
    fun `initial refresh reads all three toggles for the exact selected serial`() {
        val (scope, transport, _, viewModel) = harness()

        scope.advanceUntilIdle()

        viewModel.state.value.darkTheme shouldBe QuickToggleFieldState.Idle(false)
        viewModel.state.value.showTouches shouldBe QuickToggleFieldState.Idle(false)
        viewModel.state.value.animations shouldBe QuickToggleFieldState.Idle(AnimationsSummary.AllOn)
        transport.requests.filterIsInstance<AdbDeviceRequest>().forEach { it.serial shouldBe serialA }
    }

    @Test
    fun `dark theme toggle applies then reflects the readback, not the assumed write`() {
        val settings = FakeDeviceSettings()
        val (scope, transport, _, viewModel) = harness(mapOf(serialA to settings))
        scope.advanceUntilIdle()

        // The device silently refuses/ignores the write; the readback still reports the true value.
        transport.overrides[serialA to "cmd uimode night"] = AdbTextResult(
            outcome = AdbOutcome.Completed(0),
            stdout = "Night mode: no",
            stderr = "",
        )

        viewModel.handle(QuickTogglesIntent.SetDarkTheme(true))
        scope.advanceUntilIdle()

        viewModel.state.value.darkTheme shouldBe QuickToggleFieldState.Idle(false)
    }

    @Test
    fun `a partially failed animations-off write is reconciled by the final readback as Mixed`() {
        val settings = FakeDeviceSettings()
        val (scope, transport, _, viewModel) = harness(mapOf(serialA to settings))
        scope.advanceUntilIdle()

        // The window write never lands on the device; transition and animator succeed normally.
        transport.overrides[serialA to "settings put global window_animation_scale 0"] = AdbTextResult(
            outcome = AdbOutcome.TransportFailure("device busy"),
            stdout = "",
            stderr = "",
        )

        viewModel.handle(QuickTogglesIntent.SetAnimationsOff(true))
        scope.advanceUntilIdle()

        viewModel.state.value.animations shouldBe
            QuickToggleFieldState.Idle(AnimationsSummary.Mixed(window = 1f, transition = 0f, animatorDuration = 0f))
    }

    @Test
    fun `a timed-out readback is reported as Error, never a false success`() {
        val settings = FakeDeviceSettings()
        val (scope, transport, _, viewModel) = harness(mapOf(serialA to settings))
        scope.advanceUntilIdle()

        transport.overrides[serialA to "cmd uimode night"] = AdbTextResult(
            outcome = AdbOutcome.TimedOut,
            stdout = "",
            stderr = "",
        )

        viewModel.handle(QuickTogglesIntent.SetDarkTheme(true))
        scope.advanceUntilIdle()

        viewModel.state.value.darkTheme shouldBe QuickToggleFieldState.Error("Timed out", lastKnown = false)
    }

    @Test
    fun `cancelling the owning scope mid-write leaves no uncaught exception and no further mutation`() {
        val settings = FakeDeviceSettings()
        val (scope, transport, _, viewModel) = harness(mapOf(serialA to settings))
        scope.advanceUntilIdle()

        transport.delays[serialA to "settings put system show_touches 1"] = 1_000

        viewModel.handle(QuickTogglesIntent.SetShowTouches(true))
        scope.runCurrent()

        scope.cancel()
        scope.advanceUntilIdle()

        settings.showTouches shouldBe false
    }

    @Test
    fun `a rapid second toggle supersedes the first without interleaving or corrupting the result`() {
        val settings = FakeDeviceSettings()
        val (scope, transport, _, viewModel) = harness(mapOf(serialA to settings))
        scope.advanceUntilIdle()

        transport.delays[serialA to "settings put system show_touches 1"] = 100

        viewModel.handle(QuickTogglesIntent.SetShowTouches(true))
        scope.runCurrent()
        viewModel.handle(QuickTogglesIntent.SetShowTouches(false))
        scope.advanceUntilIdle()

        // The first write's effect never lands: it was cancelled mid-delay by the second intent.
        settings.showTouches shouldBe false
        viewModel.state.value.showTouches shouldBe QuickToggleFieldState.Idle(false)
    }

    @Test
    fun `a device switch mid-toggle suppresses the stale readback from applying to the new device`() {
        val settingsA = FakeDeviceSettings()
        val settingsB = FakeDeviceSettings(darkTheme = false)
        val (scope, transport, selectedDeviceState, viewModel) =
            harness(mapOf(serialA to settingsA, serialB to settingsB), initial = onlineState(serialA))
        scope.advanceUntilIdle()

        transport.delays[serialA to "cmd uimode night"] = 1_000

        viewModel.handle(QuickTogglesIntent.SetDarkTheme(true))
        scope.runCurrent()
        // settingsA.darkTheme is now true (the write landed); the readback for serial A is still
        // in flight, delayed, when the device switches.
        selectedDeviceState.value = onlineState(serialB)
        scope.advanceUntilIdle()

        // The new device's own (unrelated) state won, not serial A's stale readback.
        viewModel.state.value.darkTheme shouldBe QuickToggleFieldState.Idle(false)
        transport.requests.filterIsInstance<AdbDeviceRequest>().last().serial shouldBe serialB
    }
}
