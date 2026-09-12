@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.recording

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.process.ByteSink
import dev.acme.adbtoolbox.domain.recording.RecordingSessionError
import dev.acme.adbtoolbox.domain.recording.RecordingSessionState
import dev.acme.adbtoolbox.domain.recording.RecordingStartOutcome
import dev.acme.adbtoolbox.domain.time.FakeMonotonicClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/**
 * A deterministic [AdbTransport] test double whose `executeStream` hands each request to a
 * caller-supplied flow factory — unlike [dev.acme.adbtoolbox.domain.adb.FakeAdbTransport]'s fixed
 * scripted list, this lets a test simulate a `screenrecord` session that runs indefinitely (via
 * [awaitCancellation]) until the manager cancels it, which a finite list cannot represent (mirrors
 * `MirroringSessionManagerTest`'s own `ScriptedProcessExecutor`).
 */
private class ScriptedAdbTransport(
    private val streamScript: (AdbRequest) -> Flow<AdbStreamEvent> = {
        flowOf(AdbStreamEvent.Completed(AdbOutcome.Completed(0)))
    },
    private val binaryScript: suspend (AdbRequest, ByteSink) -> AdbOutcome = { _, _ -> AdbOutcome.Completed(0) },
    private val textScript: (AdbRequest) -> AdbTextResult = {
        AdbTextResult(AdbOutcome.Completed(0), stdout = "", stderr = "")
    },
) : AdbTransport {

    val streamRequests = mutableListOf<AdbRequest>()
    val binaryRequests = mutableListOf<AdbRequest>()
    val textRequests = mutableListOf<AdbRequest>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        textRequests += request
        return textScript(request)
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> {
        streamRequests += request
        return streamScript(request)
    }

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome {
        binaryRequests += request
        return binaryScript(request, sink)
    }
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")
private val fixedInstant = Instant.parse("2026-09-01T12:00:00Z")

/** A `screenrecord` remote process that "started" and then runs forever until cancelled — models
 * the real, indefinite-until-stopped-or-capped recording. */
private fun runningForeverFlow(): Flow<AdbStreamEvent> = flow { awaitCancellation() }

private fun writesBytes(bytes: ByteArray = byteArrayOf(1, 2, 3)): suspend (AdbRequest, ByteSink) -> AdbOutcome =
    { _, sink -> sink.write(bytes); AdbOutcome.Completed(0) }

class RecordingSessionManagerTest {

    private class RecordingHarness(
        val scope: TestScope,
        val transport: ScriptedAdbTransport,
        val manager: RecordingSessionManager,
        val captureDestination: FakeCaptureDestination,
        val monotonicClock: FakeMonotonicClock,
    ) {
        /** Advances past the coroutine's first dispatch so a started session has actually entered
         * [RecordingSessionState.Recording] (mirrors `MirroringSessionManagerTest`'s own
         * `advanceTimeBy(1); runCurrent()` pairing after every `start()`). */
        fun settle() {
            scope.advanceTimeBy(1)
            scope.runCurrent()
        }
    }

    private fun harness(
        streamScript: (AdbRequest) -> Flow<AdbStreamEvent> = { runningForeverFlow() },
        binaryScript: suspend (AdbRequest, ByteSink) -> AdbOutcome = writesBytes(),
        textScript: (AdbRequest) -> AdbTextResult = { AdbTextResult(AdbOutcome.Completed(0), "", "") },
        captureDestination: FakeCaptureDestination = FakeCaptureDestination(),
    ): RecordingHarness {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val transport = ScriptedAdbTransport(streamScript, binaryScript, textScript)
        val monotonicClock = FakeMonotonicClock()
        val manager = RecordingSessionManager(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            adbTransport = transport,
            captureDestination = captureDestination,
            fileNamePolicy = FileNamePolicy { "screen-20260901-120000.mp4" },
            monotonicClock = monotonicClock,
            clock = FixedClock(fixedInstant),
        )
        return RecordingHarness(scope, transport, manager, captureDestination, monotonicClock)
    }

    @Test
    fun `a serial with no session started is Idle`() {
        val h = harness()

        h.manager.stateFor(serialA).value shouldBe RecordingSessionState.Idle(serialA)
    }

    @Test
    fun `starting sets Starting synchronously, then Recording once the session dispatches`() = runTest {
        val h = harness()
        h.monotonicClock.advanceBy(5.seconds)

        h.manager.start(serialA) shouldBe RecordingStartOutcome.Started
        h.manager.stateFor(serialA).value shouldBe RecordingSessionState.Starting(serialA)

        h.settle()

        val state = h.manager.stateFor(serialA).value
        state.shouldBeInstanceOf<RecordingSessionState.Recording>()
        (state as RecordingSessionState.Recording).startedAt shouldBe 5.seconds
    }

    @Test
    fun `starting a second session for the same serial while one is active is rejected`() = runTest {
        val h = harness()

        h.manager.start(serialA) shouldBe RecordingStartOutcome.Started
        h.settle()

        h.manager.start(serialA) shouldBe RecordingStartOutcome.Rejected
        h.transport.streamRequests.size shouldBe 1
    }

    @Test
    fun `sessions for different serials are independent`() = runTest {
        val h = harness()

        h.manager.start(serialA) shouldBe RecordingStartOutcome.Started
        h.manager.start(serialB) shouldBe RecordingStartOutcome.Started
        h.settle()

        h.manager.stateFor(serialA).value.shouldBeInstanceOf<RecordingSessionState.Recording>()
        h.manager.stateFor(serialB).value.shouldBeInstanceOf<RecordingSessionState.Recording>()
        h.transport.streamRequests.size shouldBe 2
    }

    @Test
    fun `the start command is a literal screenrecord argv against the remote path, never a shell string`() = runTest {
        val h = harness()

        h.manager.start(serialA)
        h.settle()

        val request = h.transport.streamRequests.single() as AdbDeviceRequest
        request.serial shouldBe serialA
        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "screenrecord '/sdcard/screen-20260901-120000.mp4'"
    }

    @Test
    fun `stopping a recording session gracefully cancels the stream, pulls, and reaches Saved`() = runTest {
        val h = harness()

        h.manager.start(serialA)
        h.settle()

        h.manager.stop(serialA)
        h.manager.stateFor(serialA).value shouldBe RecordingSessionState.Stopping(serialA)

        h.settle()

        val state = h.manager.stateFor(serialA).value
        state.shouldBeInstanceOf<RecordingSessionState.Saved>()
        (state as RecordingSessionState.Saved).location shouldBe CaptureLocation("/fake/captures/screen-20260901-120000.mp4")
        h.captureDestination.targets.single().committed shouldBe true
    }

    @Test
    fun `stopping pulls via a literal cat exec-out argv, never the sync pull protocol`() = runTest {
        val h = harness()

        h.manager.start(serialA)
        h.settle()
        h.manager.stop(serialA)
        h.settle()

        val pullRequest = h.transport.binaryRequests.single() as AdbDeviceRequest
        (pullRequest.operation as AdbOperation.Exec).arguments shouldBe
            listOf("cat", "/sdcard/screen-20260901-120000.mp4")
    }

    @Test
    fun `a saved recording issues a remote cleanup of the recorded file`() = runTest {
        val h = harness()

        h.manager.start(serialA)
        h.settle()
        h.manager.stop(serialA)
        h.settle()

        val cleanupRequest = h.transport.textRequests.single() as AdbDeviceRequest
        (cleanupRequest.operation as AdbOperation.Shell).command.render() shouldBe
            "rm -f '/sdcard/screen-20260901-120000.mp4'"
    }

    @Test
    fun `no orphan remote process survives stop — the underlying stream is cancelled`() = runTest {
        var cancelled = false
        val h = harness(streamScript = {
            flow {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        })

        h.manager.start(serialA)
        h.settle()
        h.manager.stop(serialA)
        h.settle()

        cancelled shouldBe true
    }

    @Test
    fun `starting again while a prior session is Stopping is rejected, never a second owned process`() = runTest {
        val h = harness()

        h.manager.start(serialA)
        h.settle()
        h.manager.stop(serialA)
        h.manager.stateFor(serialA).value shouldBe RecordingSessionState.Stopping(serialA)

        // The teardown/pull coroutine has not yet completed (no settle() since stop()) — a caller
        // racing a second start() during this window must not spawn a second remote process.
        h.manager.start(serialA) shouldBe RecordingStartOutcome.Rejected

        h.settle()
        h.transport.streamRequests.size shouldBe 1
    }

    @Test
    fun `ADB's own maximum-duration auto-completion reaches Saved without an explicit stop`() = runTest {
        val h = harness(streamScript = { flowOf(AdbStreamEvent.Completed(AdbOutcome.Completed(0))) })

        h.manager.start(serialA)
        h.settle()

        h.manager.stateFor(serialA).value.shouldBeInstanceOf<RecordingSessionState.Saved>()
    }

    @Test
    fun `stop succeeding but the pull failing is a truthful, explicit partial failure, not a false Saved`() = runTest {
        val h = harness(binaryScript = { _, _ -> AdbOutcome.TimedOut })

        h.manager.start(serialA)
        h.settle()
        h.manager.stop(serialA)
        h.settle()

        val state = h.manager.stateFor(serialA).value
        state.shouldBeInstanceOf<RecordingSessionState.Error>()
        val error = (state as RecordingSessionState.Error).error
        error.shouldBeInstanceOf<RecordingSessionError.PullFailure>()
        (error as RecordingSessionError.PullFailure).reason shouldBe "Recording pull timed out"
        h.captureDestination.targets.single().discarded shouldBe true
        // The remote cleanup command must never run for a pull that never actually retrieved the
        // file — deleting it would destroy the only copy left, contradicting "safe remnant cleanup".
        h.transport.textRequests shouldBe emptyList()
    }

    @Test
    fun `a device disconnect during pull is a PullFailure without a remaining-remote-file claim`() = runTest {
        val h = harness(binaryScript = { _, _ -> AdbOutcome.TransportFailure("device offline") })

        h.manager.start(serialA)
        h.settle()
        h.manager.stop(serialA)
        h.settle()

        val state = h.manager.stateFor(serialA).value
        state.shouldBeInstanceOf<RecordingSessionState.Error>()
        val error = (state as RecordingSessionState.Error).error as RecordingSessionError.PullFailure
        error.remoteFileRemaining shouldBe false
    }

    @Test
    fun `a pull that completes but reports a non-zero exit is recoverable — the remote file is presumed to remain`() =
        runTest {
            val h = harness(binaryScript = { _, sink -> sink.write(byteArrayOf(1)); AdbOutcome.Completed(1) })

            h.manager.start(serialA)
            h.settle()
            h.manager.stop(serialA)
            h.settle()

            val state = h.manager.stateFor(serialA).value
            state.shouldBeInstanceOf<RecordingSessionState.Error>()
            val error = (state as RecordingSessionState.Error).error as RecordingSessionError.PullFailure
            error.remoteFileRemaining shouldBe true
        }

    @Test
    fun `a pull producing no bytes is treated as a failure, never a false empty Saved`() = runTest {
        val h = harness(binaryScript = { _, _ -> AdbOutcome.Completed(0) })

        h.manager.start(serialA)
        h.settle()
        h.manager.stop(serialA)
        h.settle()

        val state = h.manager.stateFor(serialA).value
        state.shouldBeInstanceOf<RecordingSessionState.Error>()
        h.captureDestination.targets.single().discarded shouldBe true
    }

    @Test
    fun `a new start is accepted once a prior session has reached a terminal Saved state`() = runTest {
        val h = harness()

        h.manager.start(serialA)
        h.settle()
        h.manager.stop(serialA)
        h.settle()
        h.manager.stateFor(serialA).value.shouldBeInstanceOf<RecordingSessionState.Saved>()

        h.manager.start(serialA) shouldBe RecordingStartOutcome.Started
        h.settle()
        h.transport.streamRequests.size shouldBe 2
    }

    @Test
    fun `a new start is accepted once a prior session has reached a terminal Error state`() = runTest {
        val h = harness(binaryScript = { _, _ -> AdbOutcome.TimedOut })

        h.manager.start(serialA)
        h.settle()
        h.manager.stop(serialA)
        h.settle()
        h.manager.stateFor(serialA).value.shouldBeInstanceOf<RecordingSessionState.Error>()

        h.manager.start(serialA) shouldBe RecordingStartOutcome.Started
        h.settle()
        h.transport.streamRequests.size shouldBe 2
    }

    @Test
    fun `disposal via scope cancellation tears down a recording session's remote process`() = runTest {
        var cancelled = false
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher + Job())
        val transport = ScriptedAdbTransport(streamScript = {
            flow {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        })
        val manager = RecordingSessionManager(
            scope = sessionScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            adbTransport = transport,
            captureDestination = FakeCaptureDestination(),
            fileNamePolicy = FileNamePolicy { "screen-20260901-120000.mp4" },
            monotonicClock = FakeMonotonicClock(),
            clock = FixedClock(fixedInstant),
        )

        manager.start(serialA)
        advanceTimeBy(1)
        runCurrent()

        sessionScope.cancel()
        runCurrent()

        cancelled shouldBe true
    }

    @Test
    fun `stop is a no-op for a serial that is not currently recording`() = runTest {
        val h = harness()

        h.manager.stop(serialA)

        h.manager.stateFor(serialA).value shouldBe RecordingSessionState.Idle(serialA)
        h.transport.streamRequests shouldBe emptyList()
    }

    @Test
    fun `server-shaped requests are never issued by the recording session manager`() = runTest {
        val h = harness()

        h.manager.start(serialA)
        h.settle()

        h.transport.streamRequests.none { it is AdbServerRequest } shouldBe true
    }
}
