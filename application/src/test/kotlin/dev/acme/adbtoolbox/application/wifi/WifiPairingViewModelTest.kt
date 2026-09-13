@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.wifi

import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.FakeDeviceListRefresher
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.wifi.FakeWifiPairingInputPort
import dev.acme.adbtoolbox.domain.wifi.WifiPairingInput
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private class WifiPairingTestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val FIXED_CLOCK = object : kotlinx.datetime.Clock {
    override fun now(): Instant = Instant.parse("2026-09-13T12:00:00Z")
}

private const val PAIRING_ADDRESS = "192.168.1.42:37000"
private const val PAIRING_CODE = "123456"
private const val CONNECT_ADDRESS = "192.168.1.42:5555"

/**
 * [WifiPairingViewModel] orchestrates task 039's device-bar-triggered flow end to end against
 * [FakeAdbTransport]/[FakeWifiPairingInputPort] — no real dialog, hardware, or ADB needed — and
 * proves the flow never leaks the pairing code into its own state or into a posted feedback message.
 */
class WifiPairingViewModelTest {

    private class Harness(
        input: WifiPairingInput = WifiPairingInput.Cancelled,
        transport: AdbTransport = FakeAdbTransport(
            textScript = { request ->
                when ((request as AdbServerRequest).arguments.first()) {
                    "pair" -> AdbTextResult(AdbOutcome.Completed(0), "Successfully paired", "")
                    else -> AdbTextResult(AdbOutcome.Completed(0), "connected to $CONNECT_ADDRESS", "")
                }
            },
        ),
        val refresher: FakeDeviceListRefresher = FakeDeviceListRefresher(),
    ) {
        val scope = TestScope()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = WifiPairingTestDispatchers(dispatcher)
        val inputPort = FakeWifiPairingInputPort(listOf(input))
        val feedback = FeedbackViewModel(scope, dispatchers)
        val viewModel = WifiPairingViewModel(
            scope = scope,
            dispatchers = dispatchers,
            inputPort = inputPort,
            useCase = WifiPairingUseCase(transport, refresher),
            feedback = feedback,
            clock = FIXED_CLOCK,
        )
    }

    @Test
    fun `cancelling the input dialog issues no command and leaves state idle`() = runTest {
        val h = Harness(input = WifiPairingInput.Cancelled)

        h.viewModel.handle(WifiPairingIntent.Launch)
        h.scope.runCurrent()

        h.viewModel.state.value shouldBe WifiPairingViewState(isBusy = false)
        h.feedback.state.value.toasts shouldBe emptyList()
        h.inputPort.invocationCount shouldBe 1
    }

    @Test
    fun `a successful submission refreshes discovery and posts a success toast`() = runTest {
        val h = Harness(
            input = WifiPairingInput.Submitted(PAIRING_ADDRESS, PAIRING_CODE, CONNECT_ADDRESS),
        )

        h.viewModel.handle(WifiPairingIntent.Launch)
        h.scope.runCurrent()
        h.refresher.complete()
        h.scope.runCurrent()

        h.viewModel.state.value shouldBe WifiPairingViewState(isBusy = false)
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Success
        h.refresher.callCount shouldBe 1
    }

    @Test
    fun `an invalid pairing code is rejected before any command is issued`() = runTest {
        val transport = FakeAdbTransport()
        val h = Harness(
            input = WifiPairingInput.Submitted(PAIRING_ADDRESS, "abc", CONNECT_ADDRESS),
            transport = transport,
        )

        h.viewModel.handle(WifiPairingIntent.Launch)
        h.scope.runCurrent()

        transport.textRequests shouldBe emptyList()
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Error
    }

    @Test
    fun `a rejected pairing code never appears in the posted feedback message`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(1), "", "Failed: Wrong pairing code") },
        )
        val harness = Harness(
            input = WifiPairingInput.Submitted(PAIRING_ADDRESS, "654321", CONNECT_ADDRESS),
            transport = transport,
        )

        harness.viewModel.handle(WifiPairingIntent.Launch)
        harness.scope.runCurrent()

        val toast = harness.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        toast.text.contains("654321") shouldBe false
    }

    @Test
    fun `cancelling the active job stops the flow without posting a success toast`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gatedTransport = object : AdbTransport {
            override suspend fun executeText(request: dev.acme.adbtoolbox.domain.adb.AdbRequest): AdbTextResult {
                gate.await()
                return AdbTextResult(AdbOutcome.Completed(0), "Successfully paired", "")
            }
            override fun executeStream(request: dev.acme.adbtoolbox.domain.adb.AdbRequest) =
                throw UnsupportedOperationException()
            override suspend fun executeBinary(
                request: dev.acme.adbtoolbox.domain.adb.AdbRequest,
                sink: dev.acme.adbtoolbox.domain.process.ByteSink,
            ) = throw UnsupportedOperationException()
        }
        val h = Harness(
            input = WifiPairingInput.Submitted(PAIRING_ADDRESS, PAIRING_CODE, CONNECT_ADDRESS),
            transport = gatedTransport,
        )

        h.viewModel.handle(WifiPairingIntent.Launch)
        h.scope.runCurrent()
        h.viewModel.handle(WifiPairingIntent.Cancel)
        gate.complete(Unit)
        h.scope.runCurrent()

        h.feedback.state.value.toasts.none { it.severity == FeedbackSeverity.Success } shouldBe true
        h.refresher.callCount shouldBe 0
    }

    @Test
    fun `disposing cancels an in-flight pairing call`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gatedTransport = object : AdbTransport {
            override suspend fun executeText(request: dev.acme.adbtoolbox.domain.adb.AdbRequest): AdbTextResult {
                gate.await()
                return AdbTextResult(AdbOutcome.Completed(0), "Successfully paired", "")
            }
            override fun executeStream(request: dev.acme.adbtoolbox.domain.adb.AdbRequest) =
                throw UnsupportedOperationException()
            override suspend fun executeBinary(
                request: dev.acme.adbtoolbox.domain.adb.AdbRequest,
                sink: dev.acme.adbtoolbox.domain.process.ByteSink,
            ) = throw UnsupportedOperationException()
        }
        val h = Harness(
            input = WifiPairingInput.Submitted(PAIRING_ADDRESS, PAIRING_CODE, CONNECT_ADDRESS),
            transport = gatedTransport,
        )

        h.viewModel.handle(WifiPairingIntent.Launch)
        h.scope.runCurrent()
        h.viewModel.dispose()
        gate.complete(Unit)
        h.scope.runCurrent()

        h.refresher.callCount shouldBe 0
    }

    @Test
    fun `launching while a flow is already in progress is a no-op`() = runTest {
        val h = Harness(input = WifiPairingInput.Submitted(PAIRING_ADDRESS, PAIRING_CODE, CONNECT_ADDRESS))

        h.viewModel.handle(WifiPairingIntent.Launch)
        h.viewModel.handle(WifiPairingIntent.Launch)
        h.scope.runCurrent()
        h.refresher.complete()
        h.scope.runCurrent()

        h.inputPort.invocationCount shouldBe 1
    }
}
