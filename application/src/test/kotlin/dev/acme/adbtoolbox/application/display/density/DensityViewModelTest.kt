@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.display.density

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.display.density.DensityReading
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

/**
 * A deterministic [AdbTransport] test double that can suspend (via [delay]) before answering, so
 * tests can exercise stale-serial suppression and rapid-change debouncing under
 * [kotlinx.coroutines.test]'s virtual clock, following [dev.acme.adbtoolbox.application.display.fontscale.FontScaleViewModelTest]'s
 * `ScriptedFontScaleTransport` fixture.
 */
private class ScriptedDensityTransport(
    private val respond: suspend (AdbDeviceRequest) -> AdbTextResult,
) : AdbTransport {
    val requests = mutableListOf<AdbDeviceRequest>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        val deviceRequest = request as AdbDeviceRequest
        requests += deviceRequest
        return respond(deviceRequest)
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = error("not used by this feature")

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = error("not used by this feature")
}

private fun AdbDeviceRequest.command(): String = (operation as AdbOperation.Shell).command.render()

private fun physical(dpi: Int) = AdbTextResult(AdbOutcome.Completed(0), stdout = "Physical density: $dpi\n", stderr = "")

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

class DensityViewModelTest {

    private fun harness(
        transport: AdbTransport,
        context: DeviceCommandContext = DeviceCommandContext.Eligible(serialA),
    ): Triple<TestScope, MutableStateFlow<DeviceCommandContext>, DensityViewModel> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val contextFlow = MutableStateFlow(context)
        val viewModel = DensityViewModel(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            useCase = DensityUseCase(transport, DensityOverrideTracker()),
            commandContext = contextFlow,
        )
        return Triple(scope, contextFlow, viewModel)
    }

    @Test
    fun `on becoming eligible, reads the exact selected device's current reading into Idle`() {
        val transport = ScriptedDensityTransport { physical(420) }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.state.value shouldBe DensityViewState.Idle(DensityReading(420, null))
        transport.requests.single().serial shouldBe serialA
    }

    @Test
    fun `applyPreset resolves the percentage against the physical dpi and reflects the readback`() {
        var applied: Int? = null
        val transport = ScriptedDensityTransport { request ->
            when {
                request.command() == "wm density" && applied == null -> physical(420)
                request.command().startsWith("wm density ") -> {
                    applied = request.command().removePrefix("wm density ").toInt()
                    AdbTextResult(AdbOutcome.Completed(0), "", "")
                }
                else -> AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\nOverride density: $applied\n", "")
            }
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(DensityIntent.ApplyPreset(125))
        scope.runCurrent()

        viewModel.state.value shouldBe DensityViewState.Idle(DensityReading(420, 525))
    }

    @Test
    fun `an out-of-range custom dpi is rejected and preserves the last known-good reading`() {
        val transport = ScriptedDensityTransport { physical(420) }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()
        val requestsBeforeApply = transport.requests.size

        viewModel.handle(DensityIntent.ApplyCustom(1))
        scope.runCurrent()

        viewModel.state.value.shouldBeInstanceOf<DensityViewState.Error>()
        (viewModel.state.value as DensityViewState.Error).reading shouldBe DensityReading(420, null)
        // Only the pre-flight read happens; the second request count stays exactly one more (the reject's own read).
        transport.requests.size shouldBe requestsBeforeApply + 1
    }

    @Test
    fun `applyCustom reports the device's actual readback, never the request, on a clamped value`() {
        var readCount = 0
        val transport = ScriptedDensityTransport { request ->
            when (request.command()) {
                "wm density 480" -> AdbTextResult(AdbOutcome.Completed(0), "", "")
                "wm density" -> {
                    readCount++
                    if (readCount == 1) physical(420) else AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\nOverride density: 460\n", "")
                }
                else -> error("unexpected command: ${request.command()}")
            }
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(DensityIntent.ApplyCustom(480))
        scope.runCurrent()

        viewModel.state.value shouldBe DensityViewState.Idle(DensityReading(420, 460))
    }

    @Test
    fun `reset writes wm density reset and reflects the physical-only readback`() {
        val transport = ScriptedDensityTransport { request ->
            if (request.command() == "wm density reset") AdbTextResult(AdbOutcome.Completed(0), "", "") else physical(420)
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(DensityIntent.Reset)
        scope.runCurrent()

        viewModel.state.value shouldBe DensityViewState.Idle(DensityReading(420, null))
    }

    @Test
    fun `a transport failure while applying surfaces as Error, preserving the last known-good reading`() {
        val transport = ScriptedDensityTransport { request ->
            if (request.command() == "wm density") physical(420) else AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "")
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(DensityIntent.ApplyPreset(100))
        scope.runCurrent()

        viewModel.state.value shouldBe DensityViewState.Error(DensityReading(420, null), "device offline")
    }

    @Test
    fun `malformed readback output surfaces as Error instead of crashing`() {
        val transport = ScriptedDensityTransport { AdbTextResult(AdbOutcome.Completed(0), "garbage", "") }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.state.value.shouldBeInstanceOf<DensityViewState.Error>()
        (viewModel.state.value as DensityViewState.Error).message shouldBe "Unexpected device output: garbage"
    }

    @Test
    fun `a rapid second apply cancels the first, and only the final value is ever reflected`() {
        var deviceDpi = 420
        val transport = ScriptedDensityTransport { request ->
            when {
                request.command() == "wm density" -> AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\nOverride density: $deviceDpi\n", "")
                request.command().startsWith("wm density ") -> {
                    delay(100)
                    deviceDpi = request.command().removePrefix("wm density ").toInt()
                    AdbTextResult(AdbOutcome.Completed(0), "", "")
                }
                else -> error("unexpected")
            }
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(DensityIntent.ApplyCustom(500))
        viewModel.handle(DensityIntent.ApplyCustom(600))
        scope.advanceTimeBy(200)
        scope.runCurrent()

        viewModel.state.value shouldBe DensityViewState.Idle(DensityReading(420, 600))
        deviceDpi shouldBe 600
    }

    @Test
    fun `a slow apply for a previously-selected device never lands on a newly-selected device`() {
        var deviceAOverride: Int? = null
        val transport = ScriptedDensityTransport { request ->
            when {
                request.serial == serialA && request.command() == "wm density" ->
                    AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\nOverride density: ${deviceAOverride ?: ""}\n", "")
                request.serial == serialA -> {
                    delay(100)
                    deviceAOverride = 500
                    AdbTextResult(AdbOutcome.Completed(0), "", "")
                }
                request.serial == serialB -> physical(320)
                else -> error("unexpected")
            }
        }
        val (scope, contextFlow, viewModel) = harness(transport, DeviceCommandContext.Eligible(serialA))
        scope.runCurrent()

        viewModel.handle(DensityIntent.ApplyCustom(500))
        scope.runCurrent()
        contextFlow.value = DeviceCommandContext.Eligible(serialB)
        scope.advanceTimeBy(200)
        scope.runCurrent()

        viewModel.state.value shouldBe DensityViewState.Idle(DensityReading(320, null))
    }

    @Test
    fun `a device switch triggers a fresh read for the new serial`() {
        val transport = ScriptedDensityTransport { request -> if (request.serial == serialA) physical(420) else physical(320) }
        val (scope, contextFlow, viewModel) = harness(transport, DeviceCommandContext.Eligible(serialA))
        scope.runCurrent()
        viewModel.state.value shouldBe DensityViewState.Idle(DensityReading(420, null))

        contextFlow.value = DeviceCommandContext.Eligible(serialB)
        scope.runCurrent()

        viewModel.state.value shouldBe DensityViewState.Idle(DensityReading(320, null))
        transport.requests.last().serial shouldBe serialB
    }

    @Test
    fun `an intent is ignored while no device is eligible`() {
        val transport = ScriptedDensityTransport { physical(420) }
        val (scope, _, viewModel) = harness(transport, DeviceCommandContext.Disabled.NoDeviceSelected)
        scope.runCurrent()

        viewModel.state.value shouldBe DensityViewState.Loading

        viewModel.handle(DensityIntent.ApplyPreset(100))
        scope.runCurrent()

        viewModel.state.value shouldBe DensityViewState.Loading
        transport.requests shouldBe emptyList()
    }
}
