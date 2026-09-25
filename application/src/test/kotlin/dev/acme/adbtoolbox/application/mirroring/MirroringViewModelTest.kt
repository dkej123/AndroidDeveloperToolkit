@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.mirroring

import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.discovery.DiscoveredTool
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolExecutablePath
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolSource
import dev.acme.adbtoolbox.domain.discovery.ToolVersion
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.nav.FakeNavigationPersistence
import dev.acme.adbtoolbox.domain.nav.NavigationState
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.domain.process.ProcessCommand
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import dev.acme.adbtoolbox.domain.process.ProcessRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class MirroringViewModelTestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private class MirroringViewModelScriptedProcessExecutor(
    private val script: (ProcessRequest) -> Flow<ProcessEvent>,
) : ProcessExecutor {
    val requests = mutableListOf<ProcessRequest>()
    override fun execute(request: ProcessRequest): Flow<ProcessEvent> {
        requests += request
        return script(request)
    }
}

private fun runningForeverFlow(): Flow<ProcessEvent> = flow {
    emit(ProcessEvent.StderrText("INFO: Device: Pixel 8 Pro"))
    awaitCancellation()
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")
private val scrcpyVersion = ToolVersion.of("2.7")

private fun discoveredScrcpy() = DiscoveredTool(
    id = ToolId.Scrcpy,
    source = ToolSource.PathFallback,
    path = ToolExecutablePath.of("/opt/homebrew/bin/scrcpy"),
    version = scrcpyVersion,
)

private fun onlineDevice(serial: DeviceSerial) = Device(serial = serial, state = DeviceConnectionState.Online)

class MirroringViewModelTest {

    private class Harness(
        toolOutcome: (ToolId) -> DiscoveryOutcome = { DiscoveryOutcome.Found(discoveredScrcpy()) },
        script: (ProcessRequest) -> Flow<ProcessEvent> = { runningForeverFlow() },
        currentOptions: () -> dev.acme.adbtoolbox.domain.mirroring.MirroringOptions = { dev.acme.adbtoolbox.domain.mirroring.MirroringOptions.DEFAULT },
    ) {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = MirroringViewModelTestDispatchers(dispatcher)
        val executor = MirroringViewModelScriptedProcessExecutor(script)
        val sessionManager = MirroringSessionManager(
            scope = scope,
            dispatchers = dispatchers,
            toolLocator = FakeToolLocator(toolOutcome),
            processExecutor = executor,
        )
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers)
        val navigation = NavigationViewModel(scope, dispatchers, FakeNavigationPersistence(), ViewId.Device)
        val viewModel = MirroringViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            sessionManager = sessionManager,
            feedback = feedback,
            navigation = navigation,
            currentOptions = currentOptions,
        )
    }

    @Test
    fun `no eligible device renders Unavailable with a disabled control policy`() = runTest {
        val h = Harness()
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Unavailable
        h.viewModel.state.value.controlPolicy.shouldBeInstanceOf<ControlPolicy.Disabled>()
    }

    @Test
    fun `selecting an eligible device with no session renders Idle and Enabled`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Idle
        h.viewModel.state.value.controlPolicy shouldBe ControlPolicy.Enabled
    }

    @Test
    fun `toggle with no eligible device posts a warning and starts no session`() = runTest {
        val h = Harness()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.runCurrent()

        h.executor.requests shouldBe emptyList()
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Warning
    }

    @Test
    fun `toggle starts mirroring for the selected serial and reaches Running`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        // Starting is set synchronously inside MirroringSessionManager.start(), observable on the
        // manager's own StateFlow before any coroutine has actually run.
        h.sessionManager.stateFor(serialA).value shouldBe
            dev.acme.adbtoolbox.domain.mirroring.MirroringSessionState.Starting(serialA)

        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()
        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Running
        h.sessionManager.stateFor(serialA).value.shouldBeInstanceOf<dev.acme.adbtoolbox.domain.mirroring.MirroringSessionState.Running>()
    }

    @Test
    fun `toggle while running stops the session and returns to Idle`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()
        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Running

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Idle
    }

    @Test
    fun `a second rapid toggle before the process reports running cancels rather than starting a duplicate`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        // The first toggle sets Starting synchronously (before its coroutine ever runs); the second
        // toggle observes that same synchronous state and issues a stop() instead of a second
        // start() — at most one owned scrcpy process can ever result, regardless of how many
        // requests were actually handed to the process executor.
        h.viewModel.handle(MirroringIntent.Toggle)
        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()

        h.executor.requests.size shouldBe 0
    }

    @Test
    fun `toggle starts a fresh session using the currently approved options, read at start time`() = runTest {
        var approved = dev.acme.adbtoolbox.domain.mirroring.MirroringOptions(stayAwake = true, maxSize = 1920)
        val h = Harness(currentOptions = { approved })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()

        h.executor.requests.single().command.arguments shouldBe
            dev.acme.adbtoolbox.domain.mirroring.buildScrcpyArguments(serialA, approved)
    }

    @Test
    fun `a currently-running session keeps its original arguments even after options are changed and re-approved`() = runTest {
        var approved = dev.acme.adbtoolbox.domain.mirroring.MirroringOptions(maxSize = 1920)
        val h = Harness(currentOptions = { approved })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()
        val startedWith = h.executor.requests.single().command.arguments

        // Approving new options while a session is already running must never retroactively
        // change the process that is already mirroring — only the next start() call observes it.
        approved = dev.acme.adbtoolbox.domain.mirroring.MirroringOptions(maxSize = 1280)
        h.scope.runCurrent()

        h.executor.requests.size shouldBe 1
        h.executor.requests.single().command.arguments shouldBe startedWith
    }

    @Test
    fun `a missing scrcpy error posts an error toast with an Open Settings recovery action`() = runTest {
        val discoveryError = DiscoveryError.ToolNotFound(ToolId.Scrcpy, listOf(ToolSource.PathFallback))
        val h = Harness(toolOutcome = { DiscoveryOutcome.Failed(discoveryError) })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.runCurrent()

        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        val action = toast.action
        requireNotNull(action)
        action.label shouldBe "Open Settings"
        h.viewModel.state.value.presentationState.shouldBeInstanceOf<MirroringPresentationState.Error>()

        action.invoke()
        h.scope.runCurrent()
        h.navigation.state.value shouldBe NavigationState.Ready(ViewId.Settings)
    }

    @Test
    fun `an external window exit posts an info toast and returns to Idle`() = runTest {
        val h = Harness(script = {
            flow {
                emit(ProcessEvent.StderrText("INFO: Device"))
                emit(ProcessEvent.Completed(ProcessOutcome.Completed(0)))
            }
        })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Idle
        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Info
        toast.text shouldBe "Mirroring window closed"
    }

    @Test
    fun `an unexpected non-zero exit such as device disconnect posts an error toast naming the exit code`() = runTest {
        val h = Harness(script = {
            flow {
                emit(ProcessEvent.StderrText("INFO: Device"))
                emit(ProcessEvent.Completed(ProcessOutcome.Completed(1)))
            }
        })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Idle
        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        toast.text shouldBe "scrcpy exited unexpectedly (code 1)"
    }

    @Test
    fun `the error toast for a failed scrcpy start includes scrcpy's own error message`() = runTest {
        val h = Harness(script = {
            flow {
                emit(ProcessEvent.StderrText("ERROR: Could not find any ADB device"))
                emit(ProcessEvent.Completed(ProcessOutcome.Completed(1)))
            }
        })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()

        h.feedback.state.value.toasts.single().text shouldBe
            "scrcpy exited unexpectedly (code 1): Could not find any ADB device"
    }

    @Test
    fun `a timed-out session posts an error toast and returns to Idle`() = runTest {
        val h = Harness(script = {
            flow {
                emit(ProcessEvent.StderrText("INFO: Device"))
                emit(ProcessEvent.Completed(ProcessOutcome.TimedOut))
            }
        })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Idle
        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        toast.text shouldBe "Mirroring timed out"
    }

    @Test
    fun `a non-tool start failure posts an error toast with no recovery action`() = runTest {
        val h = Harness(script = {
            flowOf(ProcessEvent.Completed(ProcessOutcome.StartFailure("No such file or directory")))
        })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()

        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        toast.text shouldBe "No such file or directory"
        toast.action shouldBe null
        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Error("No such file or directory")
    }

    @Test
    fun `an executable-invalid discovery error describes the invalid path with no recovery-less crash`() = runTest {
        val discoveryError = DiscoveryError.ExecutableInvalid(ToolId.Scrcpy, ToolSource.PathFallback, "/bad/path", "not executable")
        val h = Harness(toolOutcome = { DiscoveryOutcome.Failed(discoveryError) })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.runCurrent()

        val toast = h.feedback.state.value.toasts.single()
        toast.text shouldBe "The configured scrcpy path is invalid: not executable"
        toast.action?.label shouldBe "Open Settings"
    }

    @Test
    fun `a version-query-failed discovery error describes the failure`() = runTest {
        val discoveryError = DiscoveryError.VersionQueryFailed(ToolId.Scrcpy, ToolSource.PathFallback, "/opt/scrcpy", "garbled output")
        val h = Harness(toolOutcome = { DiscoveryOutcome.Failed(discoveryError) })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.runCurrent()

        val toast = h.feedback.state.value.toasts.single()
        toast.text shouldBe "scrcpy version could not be determined: garbled output"
    }

    @Test
    fun `switching the selected device suppresses the previous serial's stale session`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()
        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Running

        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialB))
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Idle

        // A's session state changing after we've switched away must not leak back into view state.
        h.sessionManager.stop(serialA)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Idle
    }

    @Test
    fun `a device becoming ineligible unsubscribes and renders Unavailable`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()
        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Running

        h.selectedDeviceState.value = SelectedDeviceState.None
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Unavailable
        h.viewModel.state.value.controlPolicy.shouldBeInstanceOf<ControlPolicy.Disabled>()
    }
}
