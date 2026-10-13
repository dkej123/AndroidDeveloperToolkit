@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.dkej123.devicecockpit.application.recording

import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbStreamEvent
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.capture.CaptureLocation
import io.github.dkej123.devicecockpit.domain.capture.FakeCaptureDestination
import io.github.dkej123.devicecockpit.domain.capture.FileNamePolicy
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveredTool
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryError
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.FakeToolLocator
import io.github.dkej123.devicecockpit.domain.discovery.ToolExecutablePath
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.discovery.ToolSource
import io.github.dkej123.devicecockpit.domain.discovery.ToolVersion
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.process.ByteSink
import io.github.dkej123.devicecockpit.domain.process.ProcessEvent
import io.github.dkej123.devicecockpit.domain.process.ProcessExecutor
import io.github.dkej123.devicecockpit.domain.process.ProcessOutcome
import io.github.dkej123.devicecockpit.domain.process.ProcessRequest
import io.github.dkej123.devicecockpit.domain.recording.RecordingSessionError
import io.github.dkej123.devicecockpit.domain.recording.RecordingSessionState
import io.github.dkej123.devicecockpit.domain.recording.ScrcpyRecording
import io.github.dkej123.devicecockpit.domain.time.FakeMonotonicClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("AAAA111")
private const val TEMP_PATH = "/captures/.capture-1.tmp"

private class RecordingScrcpyExecutor(
    private val script: (ProcessRequest) -> Flow<ProcessEvent>,
) : ProcessExecutor {
    val requests = mutableListOf<ProcessRequest>()

    override fun execute(request: ProcessRequest): Flow<ProcessEvent> {
        requests += request
        return script(request)
    }
}

private class StreamCountingAdbTransport : AdbTransport {
    val streamRequests = mutableListOf<AdbRequest>()

    override suspend fun executeText(request: AdbRequest) = AdbTextResult(AdbOutcome.Completed(0), "", "")

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> {
        streamRequests += request
        return flow { awaitCancellation() }
    }

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}

private fun tool(id: ToolId, path: String, version: String) = DiscoveredTool(
    id = id,
    source = ToolSource.PathFallback,
    path = ToolExecutablePath.of(path),
    version = ToolVersion.of(version),
)

private fun scrcpyRunsUntilStopped(): Flow<ProcessEvent> = flow {
    emit(ProcessEvent.StderrText("INFO: Recording started to mp4 file"))
    awaitCancellation()
}

class ScrcpyRecordingSessionTest {

    private class Harness(
        val scope: TestScope,
        val manager: RecordingSessionManager,
        val executor: RecordingScrcpyExecutor,
        val transport: StreamCountingAdbTransport,
        val destination: FakeCaptureDestination,
    ) {
        fun settle() {
            scope.advanceTimeBy(1)
            scope.runCurrent()
        }
    }

    private fun harness(
        scrcpyVersion: String? = "2.7",
        writablePath: String? = TEMP_PATH,
        script: (FakeCaptureDestination) -> Flow<ProcessEvent> = { scrcpyRunsUntilStopped() },
    ): Harness {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val transport = StreamCountingAdbTransport()
        val destination = FakeCaptureDestination(writablePath = writablePath)
        val executor = RecordingScrcpyExecutor { script(destination) }
        val locator = FakeToolLocator { id ->
            when {
                id == ToolId.Adb -> DiscoveryOutcome.Found(tool(ToolId.Adb, "/sdk/platform-tools/adb", "35.0.2"))
                scrcpyVersion == null -> DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Scrcpy, emptyList()))
                else -> DiscoveryOutcome.Found(tool(ToolId.Scrcpy, "/opt/bin/scrcpy", scrcpyVersion))
            }
        }
        val manager = RecordingSessionManager(
            scope = scope,
            dispatchers = object : DispatcherProvider {
                override val default = dispatcher
                override val io = dispatcher
                override val main = dispatcher
            },
            adbTransport = transport,
            captureDestination = destination,
            fileNamePolicy = FileNamePolicy { "screen-20260901-120000.mp4" },
            monotonicClock = FakeMonotonicClock(),
            clock = object : Clock {
                override fun now() = Instant.parse("2026-09-01T12:00:00Z")
            },
            toolLocator = locator,
            processExecutor = executor,
        )
        return Harness(scope, manager, executor, transport, destination)
    }

    @Test
    fun `with scrcpy 2_4 or newer the recording runs on the host through scrcpy`() {
        val h = harness()

        h.manager.start(serial)
        h.settle()

        h.manager.stateFor(serial).value.shouldBeInstanceOf<RecordingSessionState.Recording>()
        h.transport.streamRequests shouldBe emptyList()
        val request = h.executor.requests.single()
        request.command.executable shouldBe "/opt/bin/scrcpy"
        request.command.arguments shouldBe ScrcpyRecording.arguments(serial, TEMP_PATH)
        request.command.environment shouldBe mapOf("ADB" to "/sdk/platform-tools/adb")
        request.timeout shouldBe null
        request.stopGracePeriod shouldBe 10.seconds
    }

    @Test
    fun `stopping commits the file scrcpy wrote`() {
        val h = harness()
        h.manager.start(serial)
        h.settle()
        h.destination.targets.single().externalBytes = 4_096

        h.manager.stop(serial)
        h.settle()

        val target = h.destination.targets.single()
        target.committed shouldBe true
        h.manager.stateFor(serial).value shouldBe RecordingSessionState.Saved(
            serial,
            CaptureLocation("/fake/captures/screen-20260901-120000.mp4"),
        )
    }

    @Test
    fun `a scrcpy that recorded nothing is reported with its own error line and leaves no file`() {
        val h = harness(
            script = {
                flowOf(
                    ProcessEvent.StderrText("ERROR: Could not find any ADB device"),
                    ProcessEvent.Completed(ProcessOutcome.Completed(1)),
                )
            },
        )

        h.manager.start(serial)
        h.settle()

        h.destination.targets.single().discarded shouldBe true
        h.manager.stateFor(serial).value shouldBe RecordingSessionState.Error(
            serial,
            RecordingSessionError.StartFailure("scrcpy: Could not find any ADB device"),
        )
    }

    @Test
    fun `a scrcpy that exits on its own after recording keeps the file`() {
        val h = harness(
            script = { destination ->
                flow {
                    destination.targets.single().externalBytes = 10
                    emit(ProcessEvent.Completed(ProcessOutcome.Completed(0)))
                }
            },
        )

        h.manager.start(serial)
        h.settle()

        h.manager.stateFor(serial).value.shouldBeInstanceOf<RecordingSessionState.Saved>()
    }

    @Test
    fun `scrcpy older than 2_4 falls back to screenrecord`() {
        val h = harness(scrcpyVersion = "2.3")

        h.manager.start(serial)
        h.settle()

        h.executor.requests shouldBe emptyList()
        h.transport.streamRequests.size shouldBe 1
    }

    @Test
    fun `without scrcpy the recording falls back to screenrecord`() {
        val h = harness(scrcpyVersion = null)

        h.manager.start(serial)
        h.settle()

        h.executor.requests shouldBe emptyList()
        h.transport.streamRequests.size shouldBe 1
    }

    @Test
    fun `a destination a host tool cannot write to falls back to screenrecord`() {
        val h = harness(writablePath = null)

        h.manager.start(serial)
        h.settle()

        h.executor.requests shouldBe emptyList()
        h.transport.streamRequests.size shouldBe 1
    }
}
