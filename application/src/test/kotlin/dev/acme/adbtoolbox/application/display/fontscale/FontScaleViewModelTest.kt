@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.display.fontscale

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
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState
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
 * tests can exercise this task's stale-serial suppression and rapid-change debouncing under
 * [kotlinx.coroutines.test]'s virtual clock. [domain.adb.FakeAdbTransport] cannot do this — its
 * scripts are non-suspending — so this feature-local fixture stands in for it here only.
 */
private class ScriptedFontScaleTransport(
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

private fun AdbDeviceRequest.isRead(): Boolean = (operation as AdbOperation.Shell).command.render().contains(" get ")

private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout = stdout, stderr = "")

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

class FontScaleViewModelTest {

    private fun harness(
        transport: AdbTransport,
        context: DeviceCommandContext = DeviceCommandContext.Eligible(serialA),
    ): Triple<TestScope, MutableStateFlow<DeviceCommandContext>, FontScaleViewModel> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val contextFlow = MutableStateFlow(context)
        val viewModel = FontScaleViewModel(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            transport = transport,
            commandContext = contextFlow,
        )
        return Triple(scope, contextFlow, viewModel)
    }

    @Test
    fun `on becoming eligible, reads the exact selected device's current value into Idle`() {
        val transport = ScriptedFontScaleTransport { ok("1.0") }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.state.value shouldBe FontScaleState.Idle(1.0)
        transport.requests.single().serial shouldBe serialA
    }

    @Test
    fun `apply writes then reads back, and the final state is the readback value`() {
        val transport = ScriptedFontScaleTransport { request ->
            if (request.isRead()) ok("1.3") else ok("")
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(FontScaleIntent.Apply(1.3))
        scope.runCurrent()

        viewModel.state.value shouldBe FontScaleState.Idle(1.3)
        // write (get initial, then put, then get again for readback)
        transport.requests.map { it.isRead() } shouldBe listOf(true, false, true)
    }

    @Test
    fun `a device that clamps the requested value reports the device's actual readback, never the request`() {
        val transport = ScriptedFontScaleTransport { request ->
            if (request.isRead()) ok("5.0") else ok("")
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(FontScaleIntent.Apply(4.9))
        scope.runCurrent()

        // Requested 4.9, but the device clamped to 5.0 — state must reflect 5.0, the readback.
        viewModel.state.value shouldBe FontScaleState.Idle(5.0)
    }

    @Test
    fun `reset writes the platform default and reflects its readback`() {
        val transport = ScriptedFontScaleTransport { request ->
            if (request.isRead()) ok("1.0") else ok("")
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()
        viewModel.handle(FontScaleIntent.Apply(1.5))
        scope.runCurrent()

        viewModel.handle(FontScaleIntent.Reset)
        scope.runCurrent()

        viewModel.state.value shouldBe FontScaleState.Idle(1.0)
        val writes = transport.requests.filterNot { it.isRead() }
        (writes.last().operation as AdbOperation.Shell).command.render() shouldBe
            "settings put system font_scale '1.0'"
    }

    @Test
    fun `an out-of-range custom value is rejected before any command is issued`() {
        val transport = ScriptedFontScaleTransport { ok("1.0") }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()
        val requestsBeforeApply = transport.requests.size

        viewModel.handle(FontScaleIntent.Apply(5.5))
        scope.runCurrent()

        viewModel.state.value.shouldBeInstanceOf<FontScaleState.Error>()
        (viewModel.state.value as FontScaleState.Error).current shouldBe 1.0
        transport.requests.size shouldBe requestsBeforeApply
    }

    @Test
    fun `a transport failure while writing surfaces as Error, preserving the last known-good current`() {
        val transport = ScriptedFontScaleTransport { request ->
            if (request.isRead()) {
                ok("1.0")
            } else {
                AdbTextResult(AdbOutcome.TransportFailure("device offline"), stdout = "", stderr = "")
            }
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(FontScaleIntent.Apply(1.3))
        scope.runCurrent()

        viewModel.state.value shouldBe FontScaleState.Error(current = 1.0, message = "device offline")
    }

    @Test
    fun `a timed-out write surfaces as Error`() {
        val transport = ScriptedFontScaleTransport { request ->
            if (request.isRead()) ok("1.0") else AdbTextResult(AdbOutcome.TimedOut, stdout = "", stderr = "")
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(FontScaleIntent.Apply(1.3))
        scope.runCurrent()

        viewModel.state.value shouldBe FontScaleState.Error(current = 1.0, message = "Command timed out")
    }

    @Test
    fun `a cancelled write surfaces as Error`() {
        val transport = ScriptedFontScaleTransport { request ->
            if (request.isRead()) ok("1.0") else AdbTextResult(AdbOutcome.Cancelled, stdout = "", stderr = "")
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(FontScaleIntent.Apply(1.3))
        scope.runCurrent()

        viewModel.state.value shouldBe FontScaleState.Error(current = 1.0, message = "Command was cancelled")
    }

    @Test
    fun `malformed readback output surfaces as Error instead of crashing`() {
        val transport = ScriptedFontScaleTransport { ok("Security exception") }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.state.value.shouldBeInstanceOf<FontScaleState.Error>()
        (viewModel.state.value as FontScaleState.Error).message shouldBe "Unexpected device output: Security exception"
    }

    @Test
    fun `a rapid second apply cancels the first, and only the final value is ever reflected`() {
        // The scripted device applies whatever value it's written after a delay, then reports that
        // value back on read — simulating real device state so the test can assert the first apply's
        // write never lands, only the second (latest) one does.
        var deviceValue = 1.0
        val transport = ScriptedFontScaleTransport { request ->
            if (request.isRead()) {
                ok(deviceValue.toString())
            } else {
                delay(100)
                val value = (request.operation as AdbOperation.Shell).command.render()
                    .substringAfter("font_scale '").substringBefore("'").toDouble()
                deviceValue = value
                ok("")
            }
        }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.handle(FontScaleIntent.Apply(1.3))
        viewModel.handle(FontScaleIntent.Apply(1.5))
        scope.advanceTimeBy(200)
        scope.runCurrent()

        viewModel.state.value shouldBe FontScaleState.Idle(1.5)
        // The first apply's write must never have completed — only the second value was ever written.
        deviceValue shouldBe 1.5
    }

    @Test
    fun `a slow apply for a previously-selected device never lands on a newly-selected device`() {
        var deviceAValue = 1.0
        val transport = ScriptedFontScaleTransport { request ->
            when {
                request.serial == serialA && request.isRead() -> ok(deviceAValue.toString())
                request.serial == serialA && !request.isRead() -> {
                    delay(100)
                    deviceAValue = 1.3
                    ok("")
                }
                request.serial == serialB && request.isRead() -> ok("1.0")
                else -> ok("")
            }
        }
        val (scope, contextFlow, viewModel) = harness(transport, DeviceCommandContext.Eligible(serialA))
        scope.runCurrent()

        viewModel.handle(FontScaleIntent.Apply(1.3))
        scope.runCurrent()
        // Device switches to B while A's write+readback is still in flight (delay(100) pending).
        contextFlow.value = DeviceCommandContext.Eligible(serialB)
        scope.advanceTimeBy(200)
        scope.runCurrent()

        // Must reflect device B's state, never A's stale in-flight apply.
        viewModel.state.value shouldBe FontScaleState.Idle(1.0)
    }

    @Test
    fun `a device switch triggers a fresh read for the new serial`() {
        val transport = ScriptedFontScaleTransport { request ->
            if (request.serial == serialA) ok("1.3") else ok("1.5")
        }
        val (scope, contextFlow, viewModel) = harness(transport, DeviceCommandContext.Eligible(serialA))
        scope.runCurrent()
        viewModel.state.value shouldBe FontScaleState.Idle(1.3)

        contextFlow.value = DeviceCommandContext.Eligible(serialB)
        scope.runCurrent()

        viewModel.state.value shouldBe FontScaleState.Idle(1.5)
        transport.requests.last().serial shouldBe serialB
    }

    @Test
    fun `an intent is ignored while no device is eligible`() {
        val transport = ScriptedFontScaleTransport { ok("1.0") }
        val (scope, _, viewModel) = harness(transport, DeviceCommandContext.Disabled.NoDeviceSelected)
        scope.runCurrent()

        viewModel.state.value shouldBe FontScaleState.Loading

        viewModel.handle(FontScaleIntent.Apply(1.3))
        scope.runCurrent()

        viewModel.state.value shouldBe FontScaleState.Loading
        transport.requests shouldBe emptyList()
    }

    @Test
    fun `overrideContributor reflects a non-default readback for the eligible serial`() {
        val transport = ScriptedFontScaleTransport { ok("1.3") }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.overrideContributor.overridesFor(serialA).single().description shouldBe "Font scale: 1.3×"
    }

    @Test
    fun `overrideResetUseCase shares the same override tracker as overrideContributor`() {
        val transport = ScriptedFontScaleTransport { ok("1.3") }
        val (scope, _, viewModel) = harness(transport)
        scope.runCurrent()

        viewModel.overrideResetUseCase().currentValue(serialA) shouldBe "1.3"
    }
}
