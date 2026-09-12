@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.deviceactions

import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.deviceactions.DeviceActionKind
import dev.acme.adbtoolbox.domain.deviceactions.FakeTerminalLauncher
import dev.acme.adbtoolbox.domain.deviceactions.TerminalLaunchResult
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.discovery.DiscoveredTool
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolExecutablePath
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolSource
import dev.acme.adbtoolbox.domain.discovery.ToolVersion
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val FIXED_CLOCK = object : kotlinx.datetime.Clock {
    override fun now(): Instant = Instant.parse("2026-09-02T10:15:30Z")
}

private val SERIAL = DeviceSerial.of("emulator-5554")

private fun onlineDevice(serial: String) = Device(serial = DeviceSerial.of(serial), state = DeviceConnectionState.Online)

private val FOUND_ADB = DiscoveryOutcome.Found(
    DiscoveredTool(ToolId.Adb, ToolSource.PathFallback, ToolExecutablePath.of("/opt/homebrew/bin/adb"), ToolVersion.of("35.0.2")),
)

/**
 * Covers task 016's presenter-level TDD plan: duplicate in-flight prevention (any of the three
 * actions while one is already running), the no-eligible-device guard, and truthful
 * success/failure feedback publication for Reboot, Wake, and Open shell alike.
 */
class DeviceActionsViewModelTest {

    private class Harness(
        textScript: (dev.acme.adbtoolbox.domain.adb.AdbRequest) -> AdbTextResult = {
            AdbTextResult(AdbOutcome.Completed(0), "", "")
        },
        toolLocatorOutcome: (ToolId) -> DiscoveryOutcome = { FOUND_ADB },
        terminalResult: (dev.acme.adbtoolbox.domain.deviceactions.ShellSessionIntent) -> TerminalLaunchResult = { TerminalLaunchResult.Launched },
    ) {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = TestDispatcherProviderFixture(dispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val transport = FakeAdbTransport(textScript = textScript)
        val deviceActionsUseCase = DeviceActionsUseCase(transport)
        val toolLocator = FakeToolLocator(toolLocatorOutcome)
        val terminalLauncher = FakeTerminalLauncher(terminalResult)
        val openShellUseCase = OpenShellUseCase(toolLocator, terminalLauncher)
        val feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers)
        val viewModel = DeviceActionsViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            deviceActionsUseCase = deviceActionsUseCase,
            openShellUseCase = openShellUseCase,
            feedback = feedback,
            clock = FIXED_CLOCK,
        )
    }

    @Test
    fun `initial control policy mirrors the current selected-device state`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))
        h.scope.runCurrent()

        h.viewModel.state.value.controlPolicy shouldBe ControlPolicy.Enabled
    }

    @Test
    fun `reboot with no eligible device posts a warning toast and issues no ADB request`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.None

        h.viewModel.handle(DeviceActionsIntent.Reboot)
        h.scope.runCurrent()

        h.transport.textRequests shouldBe emptyList()
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Warning
    }

    @Test
    fun `a successful reboot targets the selected serial and posts a success toast`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))

        h.viewModel.handle(DeviceActionsIntent.Reboot)
        h.scope.runCurrent()

        val request = h.transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Success
        h.viewModel.state.value.busyAction shouldBe null
    }

    @Test
    fun `a failed wake posts an error toast preserving the underlying reason`() = runTest {
        val h = Harness(textScript = { AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "") })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))

        h.viewModel.handle(DeviceActionsIntent.Wake)
        h.scope.runCurrent()

        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        toast.text shouldBe "device offline"
    }

    @Test
    fun `open shell resolves adb and asks the terminal launcher to open a session, then posts success`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))

        h.viewModel.handle(DeviceActionsIntent.OpenShell)
        h.scope.runCurrent()

        h.terminalLauncher.openShellCalls.single().serial shouldBe SERIAL
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Success
    }

    @Test
    fun `a shell-adapter failure posts an error toast with the adapter's own reason`() = runTest {
        val h = Harness(terminalResult = { TerminalLaunchResult.Unavailable("Open shell requires the Terminal plugin") })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))

        h.viewModel.handle(DeviceActionsIntent.OpenShell)
        h.scope.runCurrent()

        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        toast.text shouldBe "Open shell requires the Terminal plugin"
    }

    @Test
    fun `a duplicate request while one action is already in flight is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness()
        // A transport whose executeText suspends until released, so the first request stays in
        // flight while a second (even a different action) is attempted.
        val suspendingTransport = SuspendingFakeAdbTransport(gate)
        val useCase = DeviceActionsUseCase(suspendingTransport)
        val vm = DeviceActionsViewModel(
            scope = h.scope,
            dispatchers = h.dispatchers,
            selectedDeviceState = h.selectedDeviceState,
            deviceActionsUseCase = useCase,
            openShellUseCase = h.openShellUseCase,
            feedback = h.feedback,
            clock = FIXED_CLOCK,
        )
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))

        vm.handle(DeviceActionsIntent.Reboot)
        h.scope.runCurrent()
        vm.state.value.busyAction shouldBe DeviceActionKind.Reboot

        // A second request (even a different action) while busy must be a no-op.
        vm.handle(DeviceActionsIntent.Wake)
        h.scope.runCurrent()
        suspendingTransport.callCount shouldBe 1

        gate.complete(Unit)
        h.scope.runCurrent()
        vm.state.value.busyAction shouldBe null
    }
}

private class SuspendingFakeAdbTransport(
    private val gate: CompletableDeferred<Unit>,
) : dev.acme.adbtoolbox.domain.adb.AdbTransport {
    var callCount = 0
        private set

    override suspend fun executeText(request: dev.acme.adbtoolbox.domain.adb.AdbRequest): AdbTextResult {
        callCount++
        gate.await()
        return AdbTextResult(AdbOutcome.Completed(0), "", "")
    }

    override fun executeStream(request: dev.acme.adbtoolbox.domain.adb.AdbRequest) =
        kotlinx.coroutines.flow.emptyFlow<dev.acme.adbtoolbox.domain.adb.AdbStreamEvent>()

    override suspend fun executeBinary(
        request: dev.acme.adbtoolbox.domain.adb.AdbRequest,
        sink: dev.acme.adbtoolbox.domain.process.ByteSink,
    ): AdbOutcome = AdbOutcome.Completed(0)
}
