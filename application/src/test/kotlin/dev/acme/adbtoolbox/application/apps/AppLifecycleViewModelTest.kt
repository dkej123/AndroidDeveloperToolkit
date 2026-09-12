@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.apps.AppLifecycleCommands
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private class AppLifecycleTestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val FIXED_CLOCK = object : kotlinx.datetime.Clock {
    override fun now(): Instant = Instant.parse("2026-09-12T10:15:30Z")
}

private val SERIAL = DeviceSerial.of("emulator-5554")
private const val PACKAGE = "com.acme.shop"

private fun onlineDevice(serial: DeviceSerial) = Device(serial = serial, state = DeviceConnectionState.Online)

/**
 * Covers task 023's presenter-level TDD plan: no-eligible-device / no-selected-package guards,
 * duplicate in-flight prevention scoped to the selected package, and truthful success/failure
 * feedback for Force-stop, Launch, and Restart — including restart's explicit
 * "never success after a launch failure" wording.
 */
class AppLifecycleViewModelTest {

    private class Harness(
        textScript: (AdbRequest) -> AdbTextResult = { AdbTextResult(AdbOutcome.Completed(0), "", "") },
    ) {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = AppLifecycleTestDispatcherProviderFixture(dispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val selectedPackageState = MutableStateFlow<SelectedPackageState>(SelectedPackageState.None)
        val transport = FakeAdbTransport(textScript = textScript)
        val useCase = AppLifecycleUseCase(transport)
        val feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers)
        val viewModel = AppLifecycleViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            selectedPackageState = selectedPackageState,
            appLifecycleUseCase = useCase,
            feedback = feedback,
            clock = FIXED_CLOCK,
        )

        fun selectOnlineDeviceAndPackage() {
            selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(SERIAL))
            selectedPackageState.value = SelectedPackageState.Selected(SelectedPackage(SERIAL, PACKAGE))
        }
    }

    @Test
    fun `initial state mirrors the current device control policy and package selection`() = runTest {
        val h = Harness()
        h.selectOnlineDeviceAndPackage()
        h.scope.runCurrent()

        h.viewModel.state.value.controlPolicy shouldBe ControlPolicy.Enabled
        h.viewModel.state.value.selectedPackageName shouldBe PACKAGE
    }

    @Test
    fun `force-stop with no eligible device posts a warning and issues no ADB request`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.None

        h.viewModel.handle(AppLifecycleIntent.ForceStop)
        h.scope.runCurrent()

        h.transport.textRequests shouldBe emptyList()
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Warning
        h.feedback.state.value.toasts.single().text shouldBe "No eligible device selected"
    }

    @Test
    fun `launch with an eligible device but no selected package posts a warning and issues no ADB request`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(SERIAL))
        h.selectedPackageState.value = SelectedPackageState.None

        h.viewModel.handle(AppLifecycleIntent.Launch)
        h.scope.runCurrent()

        h.transport.textRequests shouldBe emptyList()
        h.feedback.state.value.toasts.single().text shouldBe "No package selected"
    }

    @Test
    fun `a selection belonging to a different device is never targeted`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(SERIAL))
        h.selectedPackageState.value = SelectedPackageState.Selected(SelectedPackage(DeviceSerial.of("other-serial"), PACKAGE))

        h.viewModel.handle(AppLifecycleIntent.ForceStop)
        h.scope.runCurrent()

        h.transport.textRequests shouldBe emptyList()
        h.viewModel.state.value.selectedPackageName shouldBe null
    }

    @Test
    fun `a successful force-stop targets the selected serial and package and posts a success toast`() = runTest {
        val h = Harness()
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(AppLifecycleIntent.ForceStop)
        h.scope.runCurrent()

        val request = h.transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        request.operation shouldBe AdbOperation.Shell(AppLifecycleCommands.forceStop(PACKAGE))
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Success
        h.viewModel.state.value.busy shouldBe false
    }

    @Test
    fun `a successful launch posts a success toast`() = runTest {
        val h = Harness()
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(AppLifecycleIntent.Launch)
        h.scope.runCurrent()

        h.feedback.state.value.toasts.single().text shouldBe "Launched $PACKAGE"
    }

    @Test
    fun `a failed force-stop posts an error toast preserving the underlying reason`() = runTest {
        val h = Harness(textScript = { AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "") })
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(AppLifecycleIntent.ForceStop)
        h.scope.runCurrent()

        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        toast.text shouldBe "device offline"
    }

    @Test
    fun `a successful restart posts a success toast`() = runTest {
        val h = Harness()
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(AppLifecycleIntent.Restart)
        h.scope.runCurrent()

        h.feedback.state.value.toasts.single().text shouldBe "Restarted $PACKAGE"
    }

    @Test
    fun `restart never posts a success toast when launch fails after a successful force-stop`() = runTest {
        val h = Harness(
            textScript = { request ->
                val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command
                if (command == AppLifecycleCommands.forceStop(PACKAGE)) {
                    AdbTextResult(AdbOutcome.Completed(0), "", "")
                } else {
                    AdbTextResult(AdbOutcome.Completed(1), "", "No activities found to run, monkey aborted.")
                }
            },
        )
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(AppLifecycleIntent.Restart)
        h.scope.runCurrent()

        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        toast.text shouldBe "Force-stopped $PACKAGE, but launch failed: No launcher activity found for $PACKAGE"
    }

    @Test
    fun `a duplicate request for the same selected package while one is in flight is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness()
        val suspendingTransport = GatedAdbTransport(gate)
        val vm = AppLifecycleViewModel(
            scope = h.scope,
            dispatchers = h.dispatchers,
            selectedDeviceState = h.selectedDeviceState,
            selectedPackageState = h.selectedPackageState,
            appLifecycleUseCase = AppLifecycleUseCase(suspendingTransport),
            feedback = h.feedback,
            clock = FIXED_CLOCK,
        )
        h.selectOnlineDeviceAndPackage()

        vm.handle(AppLifecycleIntent.ForceStop)
        h.scope.runCurrent()
        vm.state.value.busy shouldBe true

        vm.handle(AppLifecycleIntent.Launch)
        h.scope.runCurrent()
        suspendingTransport.callCount shouldBe 1

        gate.complete(Unit)
        h.scope.runCurrent()
        vm.state.value.busy shouldBe false
    }

    @Test
    fun `an in-flight action for one package never marks a different selected package busy`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness()
        val suspendingTransport = GatedAdbTransport(gate)
        val useCase = AppLifecycleUseCase(suspendingTransport)
        val vm = AppLifecycleViewModel(
            scope = h.scope,
            dispatchers = h.dispatchers,
            selectedDeviceState = h.selectedDeviceState,
            selectedPackageState = h.selectedPackageState,
            appLifecycleUseCase = useCase,
            feedback = h.feedback,
            clock = FIXED_CLOCK,
        )
        h.selectOnlineDeviceAndPackage()
        vm.handle(AppLifecycleIntent.ForceStop)
        h.scope.runCurrent()
        vm.state.value.busy shouldBe true

        // Switch the selected package while the first is still in flight.
        h.selectedPackageState.value = SelectedPackageState.Selected(SelectedPackage(SERIAL, "com.acme.other"))
        h.scope.runCurrent()

        vm.state.value.busy shouldBe false
        gate.complete(Unit)
        h.scope.runCurrent()
    }
}

private class GatedAdbTransport(private val gate: CompletableDeferred<Unit>) : AdbTransport {
    var callCount = 0
        private set

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        callCount++
        gate.await()
        return AdbTextResult(AdbOutcome.Completed(0), "", "")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = emptyFlow()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}
