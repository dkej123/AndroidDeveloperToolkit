package io.github.dkej123.devicecockpit.intellij.visual

import io.github.dkej123.devicecockpit.application.mirroring.MirroringOptionsUseCase
import io.github.dkej123.devicecockpit.application.mirroring.MirroringOptionsViewModel
import io.github.dkej123.devicecockpit.application.mirroring.MirroringOptionsViewState
import io.github.dkej123.devicecockpit.domain.mirroring.FakeMirroringOptionsRepository
import io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptionsDraft
import io.github.dkej123.devicecockpit.intellij.ui.mirroring.MirroringOptionsPanel
import io.github.dkej123.devicecockpit.application.capture.CaptureScreenshotUseCase
import io.github.dkej123.devicecockpit.application.capture.CaptureViewModel
import io.github.dkej123.devicecockpit.application.capture.CaptureViewState
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsUseCase
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsViewModel
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsViewState
import io.github.dkej123.devicecockpit.application.deviceactions.OpenShellUseCase
import io.github.dkej123.devicecockpit.application.devicefacts.DeviceFactsViewState
import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.application.mirroring.MirroringPresentationState
import io.github.dkej123.devicecockpit.application.mirroring.MirroringSessionManager
import io.github.dkej123.devicecockpit.application.mirroring.MirroringViewModel
import io.github.dkej123.devicecockpit.application.mirroring.MirroringViewState
import io.github.dkej123.devicecockpit.application.mirroring.ScrcpyAvailability
import io.github.dkej123.devicecockpit.domain.discovery.ToolVersion
import io.github.dkej123.devicecockpit.application.nav.NavigationViewModel
import io.github.dkej123.devicecockpit.application.recording.RecordingPresentationState
import io.github.dkej123.devicecockpit.application.recording.RecordingSessionManager
import io.github.dkej123.devicecockpit.application.recording.RecordingViewModel
import io.github.dkej123.devicecockpit.application.recording.RecordingViewState
import io.github.dkej123.devicecockpit.domain.adb.AdbBinaryScript
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.capture.FakeCaptureDestination
import io.github.dkej123.devicecockpit.domain.capture.FileNamePolicy
import io.github.dkej123.devicecockpit.domain.capture.RevealInFileManager
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.deviceactions.FakeTerminalLauncher
import io.github.dkej123.devicecockpit.domain.devicecontext.ControlPolicy
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactId
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactState
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactValue
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactsSnapshot
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryError
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.FakeToolLocator
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.nav.FakeNavigationPersistence
import io.github.dkej123.devicecockpit.domain.nav.ViewId
import io.github.dkej123.devicecockpit.domain.process.FakeProcessExecutor
import io.github.dkej123.devicecockpit.domain.time.FakeMonotonicClock
import io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsPanel
import io.github.dkej123.devicecockpit.intellij.ui.capture.CaptureView
import io.github.dkej123.devicecockpit.intellij.ui.deviceactions.DeviceActionsView
import io.github.dkej123.devicecockpit.intellij.ui.mirroring.MirroringView
import io.github.dkej123.devicecockpit.intellij.ui.recording.RecordingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.minutes

/**
 * The connected Device view exactly as [io.github.dkej123.devicecockpit.intellij.toolwindow.AdbToolboxToolWindowPanel]
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

    fun connected(
        serial: DeviceSerial,
        scrcpy: ScrcpyAvailability = ScrcpyAvailability.Available(ToolVersion.of("2.7")),
        /** Screen section with everything running: mirroring, recording, options open, inspector tab open. */
        active: Boolean = false,
    ): DeviceFactsPanel {
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
            optionsPanel = MirroringOptionsPanel(
                MirroringOptionsViewModel(scope, TestDispatchers, MirroringOptionsUseCase(FakeMirroringOptionsRepository())),
                scope,
                TestDispatchers,
            ).apply {
                val draft = MirroringOptionsDraft(stayAwake = true, showTouches = true, maxSize = 1920)
                render(MirroringOptionsViewState(persisted = draft, draft = draft, isLoading = false))
            },
        )
        mirroring.render(
            MirroringViewState(
                controlPolicy = ControlPolicy.Enabled,
                presentationState = if (active) MirroringPresentationState.Running else MirroringPresentationState.Idle,
                scrcpy = scrcpy,
            ),
        )
        if (active) {
            mirroring.runningLabel.text = "Mirroring · 1080×2400 @ 60 fps"
            mirroring.setOptionsOpen(true)
        }
        panel.screenToolbar.add(mirroring.toolbarPart)
        panel.mirroringSlot.add(mirroring)
        panel.screenStatusSlot.add(mirroring.statusRow)

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
        panel.screenToolbar.add(capture)

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
        recording.render(
            RecordingViewState(
                controlPolicy = ControlPolicy.Enabled,
                presentationState = if (active) RecordingPresentationState.Recording else RecordingPresentationState.Idle,
                elapsedLabel = "00:42".takeIf { active },
            ),
        )
        panel.screenToolbar.add(recording)
        panel.screenStatusSlot.add(recording.statusRow)
        panel.inspectSlot.add(
            io.github.dkej123.devicecockpit.intellij.ui.common.ScreenToolButton(
                io.github.dkej123.devicecockpit.intellij.icons.AdbToolboxIcons.Actions.layoutInspector,
                if (active) "Re-capture layout" else "Inspect layout",
            ).apply { if (active) tone = io.github.dkej123.devicecockpit.intellij.ui.common.ToolButtonTone.ACCENT },
        )

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
