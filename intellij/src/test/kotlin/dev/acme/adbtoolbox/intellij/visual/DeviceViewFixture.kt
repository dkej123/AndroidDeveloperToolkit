package dev.acme.adbtoolbox.intellij.visual

import dev.acme.adbtoolbox.application.capture.CaptureScreenshotUseCase
import dev.acme.adbtoolbox.application.capture.CaptureViewModel
import dev.acme.adbtoolbox.application.capture.CaptureViewState
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsUseCase
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewModel
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewState
import dev.acme.adbtoolbox.application.deviceactions.OpenShellUseCase
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewState
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.mirroring.MirroringPresentationState
import dev.acme.adbtoolbox.application.mirroring.MirroringSessionManager
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.application.mirroring.MirroringViewState
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.application.recording.RecordingPresentationState
import dev.acme.adbtoolbox.application.recording.RecordingSessionManager
import dev.acme.adbtoolbox.application.recording.RecordingViewModel
import dev.acme.adbtoolbox.application.recording.RecordingViewState
import dev.acme.adbtoolbox.domain.adb.AdbBinaryScript
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.deviceactions.FakeTerminalLauncher
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactId
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactValue
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactsSnapshot
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.FakeNavigationPersistence
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.domain.process.FakeProcessExecutor
import dev.acme.adbtoolbox.domain.time.FakeMonotonicClock
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import dev.acme.adbtoolbox.intellij.ui.capture.CaptureView
import dev.acme.adbtoolbox.intellij.ui.deviceactions.DeviceActionsView
import dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringView
import dev.acme.adbtoolbox.intellij.ui.recording.RecordingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.minutes

/**
 * The connected Device view exactly as [dev.acme.adbtoolbox.intellij.toolwindow.AdbToolboxToolWindowPanel]
 * composes it: [DeviceFactsPanel] with the real mirroring, capture, recording and device-action views
 * mounted into its slots. View models get inert fakes; each view is driven by `render` with the
 * supplied idle/enabled design state, never by a real coroutine round trip.
 */
internal object DeviceViewFixture {

    private object TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    fun connected(serial: DeviceSerial): DeviceFactsPanel {
        // A pre-cancelled scope: no view/view-model collector can asynchronously re-render the
        // views after the explicit design-state `render` calls below.
        val scope = CoroutineScope(SupervisorJob().apply { cancel() })
        val selected = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val feedback = FeedbackViewModel(scope = scope, dispatchers = TestDispatchers)
        val panel = DeviceFactsPanel(onCopyReport = {})

        val mirroring = MirroringView(
            MirroringViewModel(
                scope = scope,
                dispatchers = TestDispatchers,
                selectedDeviceState = selected,
                sessionManager = MirroringSessionManager(
                    scope = scope,
                    dispatchers = TestDispatchers,
                    toolLocator = FakeToolLocator { DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Scrcpy, emptyList())) },
                    processExecutor = FakeProcessExecutor { emptyList() },
                ),
                feedback = feedback,
                navigation = NavigationViewModel(scope, TestDispatchers, FakeNavigationPersistence(), ViewId.Device),
            ),
            scope,
            TestDispatchers,
        )
        mirroring.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Idle))
        panel.mirroringSlot.add(mirroring)

        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(0), "", "") },
            binaryScript = { AdbBinaryScript(emptyList(), AdbOutcome.Completed(0)) },
        )
        val capture = CaptureView(
            CaptureViewModel(
                scope = scope,
                dispatchers = TestDispatchers,
                selectedDeviceState = selected,
                captureScreenshotUseCase = CaptureScreenshotUseCase(transport, FakeCaptureDestination(), FileNamePolicy { "screen.png" }),
                revealInFileManager = RevealInFileManager {},
                feedback = feedback,
            ),
            scope,
            TestDispatchers,
        )
        capture.render(CaptureViewState(controlPolicy = ControlPolicy.Enabled, isCapturing = false))
        panel.captureSlot.add(capture)

        val recording = RecordingView(
            RecordingViewModel(
                scope = scope,
                dispatchers = TestDispatchers,
                selectedDeviceState = selected,
                sessionManager = RecordingSessionManager(
                    scope = scope,
                    dispatchers = TestDispatchers,
                    adbTransport = transport,
                    captureDestination = FakeCaptureDestination(),
                    fileNamePolicy = FileNamePolicy { "screen.mp4" },
                    monotonicClock = FakeMonotonicClock(),
                ),
                revealInFileManager = RevealInFileManager {},
                feedback = feedback,
            ),
            scope,
            TestDispatchers,
        )
        recording.render(RecordingViewState(controlPolicy = ControlPolicy.Enabled, presentationState = RecordingPresentationState.Idle))
        panel.captureSlot.add(recording)

        val actions = DeviceActionsView(
            DeviceActionsViewModel(
                scope = scope,
                dispatchers = TestDispatchers,
                selectedDeviceState = selected,
                deviceActionsUseCase = DeviceActionsUseCase(transport),
                openShellUseCase = OpenShellUseCase(FakeToolLocator { DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Adb, emptyList())) }, FakeTerminalLauncher()),
                feedback = feedback,
            ),
            scope,
            TestDispatchers,
        )
        actions.render(DeviceActionsViewState(controlPolicy = ControlPolicy.Enabled, busyAction = null))
        panel.deviceActionsSlot.add(actions)

        panel.update(DeviceFactsViewState.Connected(snapshot(serial)))
        return panel
    }

    private fun snapshot(serial: DeviceSerial) = DeviceFactsSnapshot(
        serial = serial,
        facts = mapOf(
            DeviceFactId.AndroidVersion to DeviceFactState.Available(DeviceFactValue.AndroidVersion("15", 35)),
            DeviceFactId.Resolution to DeviceFactState.Available(DeviceFactValue.Resolution(1080, 2400)),
            DeviceFactId.Density to DeviceFactState.Available(DeviceFactValue.Density(428)),
            DeviceFactId.Battery to DeviceFactState.Available(DeviceFactValue.Battery(72, charging = true)),
            DeviceFactId.Abi to DeviceFactState.Available(DeviceFactValue.Abi("arm64-v8a")),
            DeviceFactId.Uptime to DeviceFactState.Available(DeviceFactValue.Uptime((4 * 60 + 12).minutes)),
        ),
    )
}
