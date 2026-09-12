@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.recording

import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.process.ByteSink
import dev.acme.adbtoolbox.domain.time.FakeMonotonicClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private class RecordingViewModelTestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private class RecordingViewModelFixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private class RecordingViewModelScriptedTransport(
    private val streamScript: (AdbRequest) -> Flow<AdbStreamEvent> = { flow { awaitCancellation() } },
    private val binaryScript: suspend (AdbRequest, ByteSink) -> AdbOutcome = { _, sink ->
        sink.write(byteArrayOf(1)); AdbOutcome.Completed(0)
    },
) : AdbTransport {
    val streamRequests = mutableListOf<AdbRequest>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult =
        AdbTextResult(AdbOutcome.Completed(0), "", "")

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> {
        streamRequests += request
        return streamScript(request)
    }

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = binaryScript(request, sink)
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")
private val fixedInstant = Instant.parse("2026-09-01T12:00:00Z")

private fun onlineDevice(serial: DeviceSerial) = Device(serial = serial, state = DeviceConnectionState.Online)

class RecordingViewModelTest {

    private class Harness(
        streamScript: (AdbRequest) -> Flow<AdbStreamEvent> = { flow { awaitCancellation() } },
        binaryScript: suspend (AdbRequest, ByteSink) -> AdbOutcome = { _, sink ->
            sink.write(byteArrayOf(1)); AdbOutcome.Completed(0)
        },
    ) {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = RecordingViewModelTestDispatchers(dispatcher)
        val transport = RecordingViewModelScriptedTransport(streamScript, binaryScript)
        val monotonicClock = FakeMonotonicClock()
        val sessionManager = RecordingSessionManager(
            scope = scope,
            dispatchers = dispatchers,
            adbTransport = transport,
            captureDestination = FakeCaptureDestination(),
            fileNamePolicy = FileNamePolicy { "screen-20260901-120000.mp4" },
            monotonicClock = monotonicClock,
            clock = RecordingViewModelFixedClock(fixedInstant),
        )
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers)
        val revealedLocations = mutableListOf<CaptureLocation>()
        val revealInFileManager = RevealInFileManager { location -> revealedLocations += location }
        val viewModel = RecordingViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            sessionManager = sessionManager,
            revealInFileManager = revealInFileManager,
            feedback = feedback,
            monotonicClock = monotonicClock,
            clock = RecordingViewModelFixedClock(fixedInstant),
        )

        fun settle() {
            scope.advanceTimeBy(1)
            scope.runCurrent()
        }
    }

    @Test
    fun `no eligible device renders Unavailable with a disabled control policy`() {
        val h = Harness()
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe RecordingPresentationState.Unavailable
        h.viewModel.state.value.controlPolicy.shouldBeInstanceOf<ControlPolicy.Disabled>()
    }

    @Test
    fun `selecting an eligible device with no session renders Idle and Enabled`() {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe RecordingPresentationState.Idle
        h.viewModel.state.value.controlPolicy shouldBe ControlPolicy.Enabled
    }

    @Test
    fun `toggle with no eligible device posts a warning and starts no session`() = runTest {
        val h = Harness()

        h.viewModel.handle(RecordingIntent.Toggle)
        h.scope.runCurrent()

        h.transport.streamRequests shouldBe emptyList()
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Warning
    }

    @Test
    fun `toggle starts a recording for the selected serial and reaches Recording with an elapsed label`() {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()

        h.viewModel.state.value.presentationState shouldBe RecordingPresentationState.Recording
        h.viewModel.state.value.elapsedLabel shouldBe "00:00"
    }

    @Test
    fun `the elapsed label advances with the shared monotonic clock while recording`() {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()

        h.monotonicClock.advanceBy(42.seconds)
        h.scope.advanceTimeBy(1_100)
        h.scope.runCurrent()

        h.viewModel.state.value.elapsedLabel shouldBe "00:42"
    }

    @Test
    fun `toggle while recording stops the session, clears the elapsed label, and returns to Idle`() {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()
        h.viewModel.state.value.presentationState shouldBe RecordingPresentationState.Recording

        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()

        h.viewModel.state.value.presentationState shouldBe RecordingPresentationState.Idle
        h.viewModel.state.value.elapsedLabel shouldBe null
    }

    @Test
    fun `a successful save posts a success toast with a Reveal action and records lastRecording`() {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()

        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()

        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Success
        val location = h.viewModel.state.value.lastRecording
        requireNotNull(location)
        toast.text shouldBe "Saved ${location.displayPath}"

        requireNotNull(toast.action).invoke()
        h.revealedLocations shouldBe listOf(location)
    }

    @Test
    fun `RevealLastRecording reveals the last saved location directly`() {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()
        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()

        h.viewModel.handle(RecordingIntent.RevealLastRecording)

        val location = h.viewModel.state.value.lastRecording
        requireNotNull(location)
        h.revealedLocations shouldBe listOf(location)
    }

    @Test
    fun `RevealLastRecording before any recording exists is a safe no-op`() {
        val h = Harness()

        h.viewModel.handle(RecordingIntent.RevealLastRecording)

        h.revealedLocations shouldBe emptyList()
    }

    @Test
    fun `a pull failure posts an error toast and returns to Idle, never leaving a stale Pulling render`() {
        val h = Harness(binaryScript = { _, _ -> AdbOutcome.TimedOut })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()

        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()

        h.viewModel.state.value.presentationState.shouldBeInstanceOf<RecordingPresentationState.Error>()
        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        toast.text shouldBe "Recording pull timed out"
        h.viewModel.state.value.lastRecording shouldBe null
    }

    @Test
    fun `ADB's own maximum-duration auto-completion is surfaced the same as an explicit stop`() {
        val h = Harness(streamScript = { flowOf(AdbStreamEvent.Completed(AdbOutcome.Completed(0))) })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()

        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Success
        h.viewModel.state.value.lastRecording shouldNotBe null
    }

    @Test
    fun `a second rapid toggle before the first has confirmed recording stops rather than starting a duplicate`() {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()

        h.viewModel.handle(RecordingIntent.Toggle)
        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()

        // The first toggle sets Starting synchronously (before its coroutine ever runs); the second
        // toggle observes that same synchronous state and issues a stop() instead of a second
        // start() — the job is cancelled before it ever dispatches, so no request is ever issued.
        h.transport.streamRequests.size shouldBe 0
    }

    @Test
    fun `switching the selected device suppresses the previous serial's stale session`() {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()
        h.viewModel.state.value.presentationState shouldBe RecordingPresentationState.Recording

        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialB))
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe RecordingPresentationState.Idle

        h.sessionManager.stop(serialA)
        h.settle()

        h.viewModel.state.value.presentationState shouldBe RecordingPresentationState.Idle
    }

    @Test
    fun `a device becoming ineligible unsubscribes, renders Unavailable, and stops ticking`() {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serialA))
        h.scope.runCurrent()
        h.viewModel.handle(RecordingIntent.Toggle)
        h.settle()
        h.viewModel.state.value.presentationState shouldBe RecordingPresentationState.Recording

        h.selectedDeviceState.value = SelectedDeviceState.None
        h.scope.runCurrent()

        h.viewModel.state.value.presentationState shouldBe RecordingPresentationState.Unavailable
        h.viewModel.state.value.controlPolicy.shouldBeInstanceOf<ControlPolicy.Disabled>()
        h.viewModel.state.value.elapsedLabel shouldBe null
    }
}
