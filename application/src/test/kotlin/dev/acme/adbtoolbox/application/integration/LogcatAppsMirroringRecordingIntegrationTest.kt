@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.integration

import dev.acme.adbtoolbox.application.apps.SelectedPackageIntent
import dev.acme.adbtoolbox.application.apps.SelectedPackageViewModel
import dev.acme.adbtoolbox.application.logcat.LogcatControlsController
import dev.acme.adbtoolbox.application.logcat.LogcatFilterUpdate
import dev.acme.adbtoolbox.application.logcat.LogcatPackagePidTracker
import dev.acme.adbtoolbox.application.logcat.LogcatPidResolver
import dev.acme.adbtoolbox.application.logcat.LogcatSessionManager
import dev.acme.adbtoolbox.application.mirroring.MirroringSessionManager
import dev.acme.adbtoolbox.application.recording.RecordingSessionManager
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.FakeSelectedPackagePersistence
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.discovery.DiscoveredTool
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolExecutablePath
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolSource
import dev.acme.adbtoolbox.domain.discovery.ToolVersion
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.logcat.FakeLogcatControlsPersistence
import dev.acme.adbtoolbox.domain.logcat.LogcatBuffer
import dev.acme.adbtoolbox.domain.mirroring.MirroringSessionState
import dev.acme.adbtoolbox.domain.mirroring.MirroringStartOutcome
import dev.acme.adbtoolbox.domain.process.ByteSink
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import dev.acme.adbtoolbox.domain.process.ProcessRequest
import dev.acme.adbtoolbox.domain.recording.RecordingSessionState
import dev.acme.adbtoolbox.domain.recording.RecordingStartOutcome
import dev.acme.adbtoolbox.domain.time.FakeMonotonicClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private class IntegrationTestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val serial = DeviceSerial.of("AAAA111")

private fun online(serial: DeviceSerial) = SelectedDeviceState.Online(
    Device(serial = serial, state = DeviceConnectionState.Online, model = null, product = null, transportId = null),
)

private fun AdbRequest.shellText(): String? =
    ((this as? dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest)?.operation as? AdbOperation.Shell)?.command?.render()

private fun logcatLine(pid: Int, sequence: Int): String =
    "09-10 12:34:56.789  $pid  456 I Sample: line $sequence"

/**
 * A single [AdbTransport] handling both `pidof` (text) and `logcat` (stream) requests, the same way
 * one real transport instance backs every feature in production (mirrors
 * [DeviceLifecycleOverrideIntegrationTest]'s shared-transport approach one file over).
 */
private class LogcatFakeAdbTransport(
    private val pidOfStdout: String = "",
    private val logcatStream: () -> Flow<AdbStreamEvent>,
) : AdbTransport {
    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        val text = request.shellText().orEmpty()
        check(text.startsWith("pidof")) { "unexpected text command: $text" }
        return AdbTextResult(AdbOutcome.Completed(0), stdout = pidOfStdout, stderr = "")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> {
        val text = request.shellText().orEmpty()
        check(text.startsWith("logcat")) { "unexpected stream command: $text" }
        return logcatStream()
    }

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}

class LogcatAppsMirroringRecordingIntegrationTest {

    @Test
    fun `selecting a package in Apps resolves its pid and Logcat's real filter engine narrows to only that process`() {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = IntegrationTestDispatchers(dispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(online(serial))

        // A trailing 4th line is required: LogcatEntryAssembler holds the most recent record
        // pending (in case following lines are its continuation) until either a new record header
        // arrives or the stream completes — with no 4th line, line 3 would never flush into the
        // buffer while this fake session keeps running indefinitely (`awaitCancellation`).
        val transport = LogcatFakeAdbTransport(pidOfStdout = "777") {
            flow {
                emit(AdbStreamEvent.Line(logcatLine(pid = 777, sequence = 1)))
                emit(AdbStreamEvent.Line(logcatLine(pid = 999, sequence = 2)))
                emit(AdbStreamEvent.Line(logcatLine(pid = 777, sequence = 3)))
                emit(AdbStreamEvent.Line(logcatLine(pid = 999, sequence = 4)))
                awaitCancellation()
            }
        }

        val selectedPackageViewModel = SelectedPackageViewModel(scope, dispatchers, FakeSelectedPackagePersistence())
        scope.runCurrent() // let persistence restore settle before selecting (task 054's race)

        val sessionManager = LogcatSessionManager(scope, dispatchers, selectedDeviceState, transport)
        val pidResolver = LogcatPidResolver(transport)
        val pidTracker = LogcatPackagePidTracker(scope, dispatchers, selectedPackageViewModel.state, pidResolver)
        val controller = LogcatControlsController(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            selectedPackageState = selectedPackageViewModel.state,
            sessionManager = sessionManager,
            pidTracker = pidTracker,
            persistence = FakeLogcatControlsPersistence(),
        )
        scope.launch(dispatchers.default) { sessionManager.start() }
        scope.advanceTimeBy(1)
        scope.runCurrent()

        selectedPackageViewModel.handle(SelectedPackageIntent.Select(serial, "com.example.app"))
        scope.advanceTimeBy(1)
        scope.runCurrent()

        val visible = controller.snapshot()
        visible.map { it.entry }.size shouldBe 2
        controller.state.value.packageFilterLabel shouldBe "com.example.app"
        controller.state.value.visibleCount shouldBe 2
        controller.state.value.totalRetainedCount shouldBe 3
    }

    @Test
    fun `logcat controls settings persist across a controller restart (project reopen)`() {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = IntegrationTestDispatchers(dispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(online(serial))
        val sharedPersistence = FakeLogcatControlsPersistence()
        val transport = LogcatFakeAdbTransport(pidOfStdout = "") { flow { awaitCancellation() } }
        val selectedPackageState = MutableStateFlow(dev.acme.adbtoolbox.domain.apps.SelectedPackageState.None as dev.acme.adbtoolbox.domain.apps.SelectedPackageState)

        fun buildController(): LogcatControlsController {
            val sessionManager = LogcatSessionManager(scope, dispatchers, selectedDeviceState, transport)
            val pidTracker = LogcatPackagePidTracker(scope, dispatchers, selectedPackageState, LogcatPidResolver(transport))
            return LogcatControlsController(
                scope = scope,
                dispatchers = dispatchers,
                selectedDeviceState = selectedDeviceState,
                selectedPackageState = selectedPackageState,
                sessionManager = sessionManager,
                pidTracker = pidTracker,
                persistence = sharedPersistence,
            )
        }

        val first = buildController()
        scope.runCurrent()
        first.handle(dev.acme.adbtoolbox.application.logcat.LogcatControlsIntent.SetMinSeverity(dev.acme.adbtoolbox.domain.logcat.LogSeverity.WARN))
        first.handle(dev.acme.adbtoolbox.application.logcat.LogcatControlsIntent.ToggleWrap)
        first.handle(dev.acme.adbtoolbox.application.logcat.LogcatControlsIntent.TogglePackageFilter)
        scope.runCurrent()

        // A fresh controller (simulating the project/tool window being reopened) reads back the
        // same persisted fields — never the just-constructed defaults.
        val second = buildController()
        scope.runCurrent()

        second.state.value.minSeverity shouldBe dev.acme.adbtoolbox.domain.logcat.LogSeverity.WARN
        second.state.value.wrap shouldBe true
        second.state.value.packageFilterOn shouldBe false
    }

    @Test
    fun `a stress burst of 10k+ logcat lines stays within the bounded buffer and batches filter updates instead of one per line`() {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = IntegrationTestDispatchers(dispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(online(serial))
        val lineCount = 12_000

        val transport = LogcatFakeAdbTransport(pidOfStdout = "") {
            flow {
                repeat(lineCount) { i -> emit(AdbStreamEvent.Line(logcatLine(pid = 1, sequence = i))) }
                awaitCancellation()
            }
        }
        val selectedPackageState = MutableStateFlow(dev.acme.adbtoolbox.domain.apps.SelectedPackageState.None as dev.acme.adbtoolbox.domain.apps.SelectedPackageState)

        // A deliberately small cap (real production default is 16MB) so 12k short lines are
        // guaranteed to exceed it and force real eviction within this test's runtime.
        val boundedBuffer = LogcatBuffer(capacityBytes = 50_000)
        val sessionManager = LogcatSessionManager(scope, dispatchers, selectedDeviceState, transport, boundedBuffer)
        val pidTracker = LogcatPackagePidTracker(scope, dispatchers, selectedPackageState, LogcatPidResolver(transport))
        val controller = LogcatControlsController(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            selectedPackageState = selectedPackageState,
            sessionManager = sessionManager,
            pidTracker = pidTracker,
            persistence = FakeLogcatControlsPersistence(),
        )
        val receivedUpdates = mutableListOf<LogcatFilterUpdate>()
        scope.launch(dispatchers.default) { controller.filterUpdates.collect { receivedUpdates += it } }
        scope.runCurrent()

        scope.launch(dispatchers.default) { sessionManager.start() }
        scope.advanceTimeBy(1)
        scope.runCurrent()

        var snapshot: dev.acme.adbtoolbox.domain.logcat.LogcatBufferSnapshot? = null
        scope.launch(dispatchers.default) { snapshot = boundedBuffer.snapshot() }
        scope.runCurrent()
        (snapshot!!.totalBytes <= boundedBuffer.capacityBytes) shouldBe true
        (snapshot!!.entries.size < lineCount) shouldBe true
        (controller.state.value.totalRetainedCount < lineCount) shouldBe true

        // Every append happens synchronously within one dispatcher slice (the fake stream never
        // suspends between lines), so the conflated `publication` StateFlow this controller reduces
        // from is only ever observed after the whole burst settles — one batch, not 12,000.
        (receivedUpdates.size <= 3) shouldBe true
    }

    @Test
    fun `scrcpy mirroring and device-side screenrecord recording for the same serial run independently`() {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = IntegrationTestDispatchers(dispatcher)

        val scrcpyVersion = ToolVersion.of("2.7")
        val discovered = DiscoveredTool(
            id = ToolId.Scrcpy,
            source = ToolSource.PathFallback,
            path = ToolExecutablePath.of("/opt/homebrew/bin/scrcpy"),
            version = scrcpyVersion,
        )
        val processExecutor = object : ProcessExecutor {
            override fun execute(request: ProcessRequest): Flow<ProcessEvent> = flow {
                emit(ProcessEvent.StderrText("INFO: Device: Pixel 8 Pro"))
                awaitCancellation()
            }
        }
        val mirroring = MirroringSessionManager(
            scope = scope,
            dispatchers = dispatchers,
            toolLocator = FakeToolLocator { DiscoveryOutcome.Found(discovered) },
            processExecutor = processExecutor,
        )

        val recordingTransport = object : AdbTransport {
            override suspend fun executeText(request: AdbRequest) = AdbTextResult(AdbOutcome.Completed(0), "", "")
            override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = flow { awaitCancellation() }
            override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome {
                sink.write(byteArrayOf(1, 2, 3))
                return AdbOutcome.Completed(0)
            }
        }
        val recording = RecordingSessionManager(
            scope = scope,
            dispatchers = dispatchers,
            adbTransport = recordingTransport,
            captureDestination = FakeCaptureDestination(),
            fileNamePolicy = FileNamePolicy { "screen-20260901-120000.mp4" },
            monotonicClock = FakeMonotonicClock(),
            clock = object : Clock {
                override fun now(): Instant = Instant.parse("2026-09-01T12:00:00Z")
            },
        )

        mirroring.start(serial) shouldBe MirroringStartOutcome.Started
        recording.start(serial) shouldBe RecordingStartOutcome.Started
        scope.advanceTimeBy(1)
        scope.runCurrent()

        mirroring.stateFor(serial).value.shouldBeInstanceOf<MirroringSessionState.Running>()
        recording.stateFor(serial).value.shouldBeInstanceOf<RecordingSessionState.Recording>()

        // Stopping recording must never disturb the independent, still-running mirroring session.
        recording.stop(serial)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        mirroring.stateFor(serial).value.shouldBeInstanceOf<MirroringSessionState.Running>()
        (recording.stateFor(serial).value is RecordingSessionState.Recording) shouldBe false
    }
}
