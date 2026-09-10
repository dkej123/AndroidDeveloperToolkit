@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.logcat.LogcatCommand
import dev.acme.adbtoolbox.domain.logcat.LogcatEntry
import dev.acme.adbtoolbox.domain.logcat.LogcatSessionError
import dev.acme.adbtoolbox.domain.logcat.LogcatSessionState
import dev.acme.adbtoolbox.domain.logcat.LogcatStartOutcome
import dev.acme.adbtoolbox.domain.logcat.LogcatStopReason
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class LogcatTestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private class ScriptedLogcatTransport(
    private val script: (AdbRequest) -> Flow<AdbStreamEvent>,
) : AdbTransport {
    val requests = mutableListOf<AdbRequest>()

    override suspend fun executeText(request: AdbRequest) = AdbTextResult(AdbOutcome.Completed(0), "", "")

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> {
        requests += request
        return script(request)
    }

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome =
        error("not used")
}

private val logcatSerialA = DeviceSerial.of("emulator-5554")
private val logcatSerialB = DeviceSerial.of("192.168.1.20:5555")

private fun online(serial: DeviceSerial) = SelectedDeviceState.Online(
    Device(serial = serial, state = DeviceConnectionState.Online, model = null, product = null, transportId = null),
)

private fun runningLogcat(cancelled: (() -> Unit)? = null): Flow<AdbStreamEvent> = flow {
    emit(AdbStreamEvent.Line("09-10 12:34:56.789  123  456 I Sample: ready"))
    emit(AdbStreamEvent.Line("09-10 12:34:56.790  123  456 I Sample: still running"))
    try {
        awaitCancellation()
    } finally {
        cancelled?.invoke()
    }
}

class LogcatSessionManagerTest {

    private data class Harness(
        val scope: TestScope,
        val selected: MutableStateFlow<SelectedDeviceState>,
        val transport: ScriptedLogcatTransport,
        val manager: LogcatSessionManager,
    )

    private fun harness(
        initialSelection: SelectedDeviceState = SelectedDeviceState.None,
        script: (AdbRequest) -> Flow<AdbStreamEvent> = { runningLogcat() },
    ): Harness {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val selected = MutableStateFlow(initialSelection)
        val transport = ScriptedLogcatTransport(script)
        return Harness(
            scope,
            selected,
            transport,
            LogcatSessionManager(scope, LogcatTestDispatchers(dispatcher), selected, transport),
        )
    }

    @Test
    fun `an online selected device starts one exact-serial threadtime stream and publishes entries`() = runTest {
        val h = harness(initialSelection = online(logcatSerialA))

        h.scope.runCurrent()

        h.transport.requests.shouldContainExactly(LogcatCommand.request(logcatSerialA))
        h.manager.state.value shouldBe LogcatSessionState.Running(logcatSerialA)
        val entries = h.manager.buffer.snapshot().entries
        entries.size shouldBe 1
        entries.single().entry.shouldBeInstanceOf<LogcatEntry.Record>()
        h.manager.publication.value.entryCount shouldBe 1
    }

    @Test
    fun `start is idempotent while the selected serial is already starting or running`() = runTest {
        val h = harness(initialSelection = online(logcatSerialA))
        h.scope.runCurrent()

        h.manager.start() shouldBe LogcatStartOutcome.AlreadyActive
        h.manager.start() shouldBe LogcatStartOutcome.AlreadyActive

        h.transport.requests.size shouldBe 1
    }

    @Test
    fun `a silent live source remains Starting until it produces output`() = runTest {
        val h = harness(initialSelection = online(logcatSerialA), script = { flow { awaitCancellation() } })

        h.scope.runCurrent()

        h.manager.state.value shouldBe LogcatSessionState.Starting(logcatSerialA)
        h.transport.requests.size shouldBe 1
    }

    @Test
    fun `start without an eligible selected device is rejected without a request`() = runTest {
        val h = harness(initialSelection = SelectedDeviceState.None)
        h.scope.runCurrent()

        h.manager.start() shouldBe LogcatStartOutcome.NoEligibleDevice

        h.transport.requests shouldBe emptyList()
        h.manager.state.value shouldBe LogcatSessionState.Idle
    }

    @Test
    fun `stderr and a transport failure before output become bounded start diagnostics`() = runTest {
        val h = harness(initialSelection = online(logcatSerialA), script = {
            flowOf(
                AdbStreamEvent.StderrLine("cannot open log device"),
                AdbStreamEvent.Completed(AdbOutcome.TransportFailure("device offline")),
            )
        })

        h.scope.runCurrent()

        h.manager.state.value shouldBe LogcatSessionState.Error(
            logcatSerialA,
            LogcatSessionError.StartFailure("device offline", "cannot open log device"),
        )
        h.manager.buffer.snapshot().entries shouldBe emptyList()
    }

    @Test
    fun `non-zero exit after records is an unexpected exit and pending record is flushed`() = runTest {
        val h = harness(initialSelection = online(logcatSerialA), script = {
            flowOf(
                AdbStreamEvent.Line("09-10 12:34:56.789  123  456 E Sample: failed"),
                AdbStreamEvent.StderrLine("transport closed"),
                AdbStreamEvent.Completed(AdbOutcome.Completed(1)),
            )
        })

        h.scope.runCurrent()

        h.manager.state.value shouldBe LogcatSessionState.Error(
            logcatSerialA,
            LogcatSessionError.UnexpectedExit(1, "transport closed"),
        )
        h.manager.buffer.snapshot().entries.size shouldBe 1
    }

    @Test
    fun `explicit stop cancels the source and ends as requested without a false error`() = runTest {
        var cancelled = false
        val h = harness(initialSelection = online(logcatSerialA), script = { runningLogcat { cancelled = true } })
        h.scope.runCurrent()

        val stop = h.scope.async { h.manager.stop() }
        h.scope.runCurrent()
        stop.await()

        cancelled shouldBe true
        h.manager.state.value shouldBe LogcatSessionState.Stopped(logcatSerialA, LogcatStopReason.Requested)
    }

    @Test
    fun `stop racing with startup cancels the source and ends as requested`() = runTest {
        var cancelled = false
        val h = harness(initialSelection = online(logcatSerialA), script = {
            flow {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        })
        h.scope.runCurrent()
        h.manager.state.value shouldBe LogcatSessionState.Starting(logcatSerialA)

        val stop = h.scope.async { h.manager.stop() }
        h.scope.runCurrent()
        stop.await()

        cancelled shouldBe true
        h.manager.state.value shouldBe LogcatSessionState.Stopped(logcatSerialA, LogcatStopReason.Requested)
    }

    @Test
    fun `device switch cancels the old source then starts the new exact serial`() = runTest {
        val cancelledSerials = mutableListOf<DeviceSerial>()
        val h = harness(initialSelection = online(logcatSerialA), script = { request ->
            val serial = LogcatCommand.request(logcatSerialA).let { expectedA ->
                if (request == expectedA) logcatSerialA else logcatSerialB
            }
            runningLogcat { cancelledSerials += serial }
        })
        h.scope.runCurrent()

        h.selected.value = online(logcatSerialB)
        h.scope.runCurrent()

        cancelledSerials.shouldContainExactly(logcatSerialA)
        h.transport.requests.shouldContainExactly(LogcatCommand.request(logcatSerialA), LogcatCommand.request(logcatSerialB))
        h.manager.state.value shouldBe LogcatSessionState.Running(logcatSerialB)
    }

    @Test
    fun `disconnect cancels the source and is stopped without a false error`() = runTest {
        var cancelled = false
        val h = harness(initialSelection = online(logcatSerialA), script = { runningLogcat { cancelled = true } })
        h.scope.runCurrent()

        h.selected.value = SelectedDeviceState.Disconnected(logcatSerialA)
        h.scope.runCurrent()

        cancelled shouldBe true
        h.manager.state.value shouldBe
            LogcatSessionState.Stopped(logcatSerialA, LogcatStopReason.DeviceUnavailable)
    }

    @Test
    fun `local clear invalidates publication without issuing adb logcat clear`() = runTest {
        val h = harness(initialSelection = online(logcatSerialA))
        h.scope.runCurrent()
        val requestsBefore = h.transport.requests.toList()
        val cursorBefore = h.manager.publication.value.cursor

        h.manager.clear()

        h.transport.requests shouldBe requestsBefore
        h.manager.buffer.snapshot().entries shouldBe emptyList()
        h.manager.publication.value.entryCount shouldBe 0
        h.manager.publication.value.cursor.generation shouldBe cursorBefore.generation + 1
    }

    @Test
    fun `scope disposal cancels the underlying stream`() = runTest {
        var cancelled = false
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(dispatcher + Job())
        val selected = MutableStateFlow<SelectedDeviceState>(online(logcatSerialA))
        val transport = ScriptedLogcatTransport { runningLogcat { cancelled = true } }
        LogcatSessionManager(sessionScope, LogcatTestDispatchers(dispatcher), selected, transport)
        runCurrent()

        sessionScope.cancel()
        runCurrent()

        cancelled shouldBe true
    }

    @Test
    fun `flow ending without a terminal event is a stream failure`() = runTest {
        val h = harness(initialSelection = online(logcatSerialA), script = {
            flowOf(AdbStreamEvent.StderrLine("receiver vanished"))
        })

        h.scope.runCurrent()

        h.manager.state.value shouldBe LogcatSessionState.Error(
            logcatSerialA,
            LogcatSessionError.StreamFailure("logcat stream ended without a terminal event", "receiver vanished"),
        )
    }

    @Test
    fun `terminal cancellation is stopped rather than reported as an error`() = runTest {
        val h = harness(initialSelection = online(logcatSerialA), script = {
            flowOf(AdbStreamEvent.Completed(AdbOutcome.Cancelled))
        })

        h.scope.runCurrent()

        h.manager.state.value shouldBe LogcatSessionState.Stopped(logcatSerialA, LogcatStopReason.Cancelled)
    }

    @Test
    fun `timeout is a typed error`() = runTest {
        val h = harness(initialSelection = online(logcatSerialA), script = {
            flowOf(AdbStreamEvent.StderrLine("slow transport"), AdbStreamEvent.Completed(AdbOutcome.TimedOut))
        })

        h.scope.runCurrent()

        h.manager.state.value shouldBe
            LogcatSessionState.Error(logcatSerialA, LogcatSessionError.TimedOut("slow transport"))
    }

    @Test
    fun `exception from source is surfaced with bounded diagnostics`() = runTest {
        val longDiagnostic = "x".repeat(10_000)
        val h = harness(initialSelection = online(logcatSerialA), script = {
            flow {
                emit(AdbStreamEvent.StderrLine(longDiagnostic))
                error("receiver crashed")
            }
        })

        h.scope.runCurrent()

        val error = h.manager.state.value.shouldBeInstanceOf<LogcatSessionState.Error>().error
            .shouldBeInstanceOf<LogcatSessionError.StreamFailure>()
        error.reason shouldBe "receiver crashed"
        error.diagnostics.length shouldBe 8 * 1024
    }
}
