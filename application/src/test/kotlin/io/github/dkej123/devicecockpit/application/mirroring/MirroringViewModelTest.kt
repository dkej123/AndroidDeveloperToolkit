@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.dkej123.devicecockpit.application.mirroring

import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.application.nav.NavigationViewModel
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.device.Device
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.devicecontext.ControlPolicy
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveredTool
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryError
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.FakeHostPlatformProvider
import io.github.dkej123.devicecockpit.domain.discovery.FakeToolLocator
import io.github.dkej123.devicecockpit.domain.discovery.OperatingSystem
import io.github.dkej123.devicecockpit.domain.discovery.ToolExecutablePath
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.discovery.ToolSource
import io.github.dkej123.devicecockpit.domain.discovery.ToolVersion
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackSeverity
import io.github.dkej123.devicecockpit.domain.nav.FakeNavigationPersistence
import io.github.dkej123.devicecockpit.domain.nav.NavigationState
import io.github.dkej123.devicecockpit.domain.nav.ViewId
import io.github.dkej123.devicecockpit.domain.process.ProcessCommand
import io.github.dkej123.devicecockpit.domain.process.ProcessEvent
import io.github.dkej123.devicecockpit.domain.process.ProcessExecutor
import io.github.dkej123.devicecockpit.domain.process.ProcessOutcome
import io.github.dkej123.devicecockpit.domain.process.ProcessRequest
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
import kotlinx.coroutines.launch
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
        currentOptions: () -> io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptions = { io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptions.DEFAULT },
        withAvailability: Boolean = false,
    ) {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = MirroringViewModelTestDispatchers(dispatcher)
        val executor = MirroringViewModelScriptedProcessExecutor(script)
        val sessionManager = MirroringSessionManager(
            scope = scope,
            dispatchers = dispatchers,
            toolLocator = FakeToolLocator { toolOutcome(it) },
            processExecutor = executor,
        )
        val availability = if (withAvailability) {
            ScrcpyAvailabilityViewModel(scope, dispatchers, FakeToolLocator { toolOutcome(it) }, FakeHostPlatformProvider(OperatingSystem.MacOs))
        } else {
            null
        }
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
            scrcpyAvailability = availability,
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
            io.github.dkej123.devicecockpit.domain.mirroring.MirroringSessionState.Starting(serialA)

        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()
        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Running
        h.sessionManager.stateFor(serialA).value.shouldBeInstanceOf<io.github.dkej123.devicecockpit.domain.mirroring.MirroringSessionState.Running>()
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
        var approved = io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptions(stayAwake = true, maxSize = 1920)
        val h = Harness(currentOptions = { approved })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()

        h.executor.requests.single().command.arguments shouldBe
            io.github.dkej123.devicecockpit.domain.mirroring.buildScrcpyArguments(serialA, approved)
    }

    @Test
    fun `a currently-running session keeps its original arguments even after options are changed and re-approved`() = runTest {
        var approved = io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptions(maxSize = 1920)
        val h = Harness(currentOptions = { approved })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.advanceTimeBy(1)
        h.scope.runCurrent()
        val startedWith = h.executor.requests.single().command.arguments

        // Approving new options while a session is already running must never retroactively
        // change the process that is already mirroring — only the next start() call observes it.
        approved = io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptions(maxSize = 1280)
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

        val settingsRequests = mutableListOf<Unit>()
        h.scope.backgroundScope.launch { h.navigation.openSettingsRequests.collect { settingsRequests += it } }
        h.scope.runCurrent()
        action.invoke()
        h.scope.runCurrent()
        settingsRequests.size shouldBe 1
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

    @Test
    fun `a missing scrcpy is known before any click and blocks starting with the fix in the toast`() = runTest {
        val discoveryError = DiscoveryError.ToolNotFound(ToolId.Scrcpy, listOf(ToolSource.PathFallback))
        val h = Harness(toolOutcome = { DiscoveryOutcome.Failed(discoveryError) }, withAvailability = true)
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.state.value.scrcpy.shouldBeInstanceOf<ScrcpyAvailability.Missing>()

        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.runCurrent()

        h.executor.requests shouldBe emptyList()
        h.viewModel.state.value.presentationState shouldBe MirroringPresentationState.Idle
        val toast = h.feedback.state.value.toasts.single()
        toast.text shouldBe "scrcpy is not installed, or not on PATH. Install it (brew install scrcpy) or set its path in Settings."
        toast.action?.label shouldBe "Open Settings"
    }

    @Test
    fun `check again re-resolves scrcpy and re-enables mirroring once it is installed`() = runTest {
        var installed = false
        val h = Harness(
            toolOutcome = {
                if (installed) {
                    DiscoveryOutcome.Found(discoveredScrcpy())
                } else {
                    DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Scrcpy, emptyList()))
                }
            },
            withAvailability = true,
        )
        h.scope.runCurrent()

        installed = true
        h.viewModel.handle(MirroringIntent.RecheckScrcpy)
        h.scope.runCurrent()

        h.viewModel.state.value.scrcpy shouldBe ScrcpyAvailability.Available(scrcpyVersion)
    }

    @Test
    fun `open settings asks navigation to open Settings every time and keeps the current view`() = runTest {
        val h = Harness(withAvailability = true)
        h.scope.runCurrent()
        val settingsRequests = mutableListOf<Unit>()
        h.scope.backgroundScope.launch { h.navigation.openSettingsRequests.collect { settingsRequests += it } }
        h.scope.runCurrent()

        repeat(2) {
            h.viewModel.handle(MirroringIntent.OpenSettings)
            h.scope.runCurrent()
        }

        settingsRequests.size shouldBe 2
        h.navigation.state.value shouldBe NavigationState.Ready(ViewId.Device)
    }

    @Test
    fun `scrcpy disappearing after the check is noticed by the failed start`() = runTest {
        var installed = true
        val h = Harness(
            toolOutcome = {
                if (installed) {
                    DiscoveryOutcome.Found(discoveredScrcpy())
                } else {
                    DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Scrcpy, emptyList()))
                }
            },
            withAvailability = true,
        )
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.state.value.scrcpy.shouldBeInstanceOf<ScrcpyAvailability.Available>()

        installed = false
        h.viewModel.handle(MirroringIntent.Toggle)
        h.scope.runCurrent()

        h.viewModel.state.value.scrcpy.shouldBeInstanceOf<ScrcpyAvailability.Missing>()
    }
}
