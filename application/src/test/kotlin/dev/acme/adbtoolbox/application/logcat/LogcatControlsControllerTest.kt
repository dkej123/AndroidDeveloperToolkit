@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.logcat.FakeLogcatControlsPersistence
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.domain.logcat.LogcatBuffer
import dev.acme.adbtoolbox.domain.logcat.LogcatPauseState
import dev.acme.adbtoolbox.domain.logcat.LogcatPersistedControls
import dev.acme.adbtoolbox.domain.logcat.LogcatSessionError
import dev.acme.adbtoolbox.domain.logcat.LogcatSessionState
import dev.acme.adbtoolbox.domain.logcat.LogcatStopReason
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class ControlsTestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

/**
 * A controllable live logcat source: each [emit] call delivers one more threadtime line as an
 * `AdbStreamEvent.Line`, immediately followed by a daemon-marker line — [LogcatEntryAssembler] only
 * flushes a pending record once it sees the *next* line (it must check whether that one is a
 * continuation), so without the trailing marker the just-emitted line would stay invisibly pending
 * inside the session's assembler instead of landing in [LogcatSessionManager.buffer]. The marker
 * line itself becomes its own always-materialized [dev.acme.adbtoolbox.domain.logcat.LogcatEntry.DaemonMarker]
 * (excluded by any active severity/pid/search criterion, since it carries no severity or pid) — so
 * every [emit] call deterministically contributes exactly two buffer entries, with no dangling
 * pending state carried into the next call.
 */
private class ControllableLogcatTransport(
    private val pidLookup: (String) -> AdbTextResult = { AdbTextResult(AdbOutcome.Completed(0), "", "") },
) : AdbTransport {
    private val events = Channel<AdbStreamEvent>(Channel.UNLIMITED)

    fun emit(line: String) {
        events.trySend(AdbStreamEvent.Line(line))
        events.trySend(AdbStreamEvent.Line("--- beginning of flush"))
    }

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        val command = (request as dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest)
            .operation.let { it as dev.acme.adbtoolbox.domain.adb.AdbOperation.Shell }.command
        val packageName = command.render().substringAfter("'").substringBefore("'")
        return pidLookup(packageName)
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = events.receiveAsFlow()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = error("not used")
}

private val SERIAL_A = DeviceSerial.of("emulator-5554")
private val SERIAL_B = DeviceSerial.of("192.168.1.20:5555")

private fun online(serial: DeviceSerial) = SelectedDeviceState.Online(
    Device(serial = serial, state = DeviceConnectionState.Online, model = null, product = null, transportId = null),
)

private fun record(
    time: String = "09-10 12:34:56.789",
    pid: Int = 1000,
    tid: Int = 1000,
    level: Char = 'I',
    tag: String = "Sample",
    message: String = "hello",
): String = "$time $pid $tid $level $tag: $message"

class LogcatControlsControllerTest {

    private data class Harness(
        val scope: TestScope,
        val selectedDevice: MutableStateFlow<SelectedDeviceState>,
        val selectedPackage: MutableStateFlow<SelectedPackageState>,
        val transport: ControllableLogcatTransport,
        val sessionManager: LogcatSessionManager,
        val pidTracker: LogcatPackagePidTracker,
        val persistence: FakeLogcatControlsPersistence,
        val controller: LogcatControlsController,
    )

    private fun harness(
        initialDevice: SelectedDeviceState = online(SERIAL_A),
        initialPackage: SelectedPackageState = SelectedPackageState.None,
        persisted: LogcatPersistedControls = LogcatPersistedControls(),
        bufferCapacityBytes: Int = LogcatBuffer().capacityBytes,
        pidLookup: (String) -> AdbTextResult = { AdbTextResult(AdbOutcome.Completed(0), "", "") },
    ): Harness {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = ControlsTestDispatchers(dispatcher)
        val selectedDevice = MutableStateFlow(initialDevice)
        val selectedPackage = MutableStateFlow(initialPackage)
        val transport = ControllableLogcatTransport(pidLookup)
        val sessionManager = LogcatSessionManager(
            scope, dispatchers, selectedDevice, transport,
            buffer = LogcatBuffer(capacityBytes = bufferCapacityBytes),
        )
        val pidTracker = LogcatPackagePidTracker(scope, dispatchers, selectedPackage, LogcatPidResolver(transport))
        val persistence = FakeLogcatControlsPersistence(persisted)
        val controller = LogcatControlsController(
            scope, dispatchers, selectedDevice, selectedPackage, sessionManager, pidTracker, persistence,
        )
        return Harness(scope, selectedDevice, selectedPackage, transport, sessionManager, pidTracker, persistence, controller)
    }

    @Test
    fun `initial state has no filters and follows live`() = runTest {
        val h = harness()
        h.scope.runCurrent()

        h.controller.state.value.query shouldBe ""
        h.controller.state.value.minSeverity shouldBe null
        h.controller.state.value.wrap shouldBe false
        h.controller.state.value.follow shouldBe true
        h.controller.state.value.pauseState shouldBe LogcatPauseState.Resumed
        h.controller.state.value.serial shouldBe SERIAL_A
        h.controller.state.value.isDeviceEligible shouldBe true
    }

    @Test
    fun `persisted controls are restored at startup`() = runTest {
        val h = harness(persisted = LogcatPersistedControls(minSeverity = LogSeverity.WARN, packageFilterOn = false, wrap = true))
        h.scope.runCurrent()

        h.controller.state.value.minSeverity shouldBe LogSeverity.WARN
        h.controller.state.value.packageFilterOn shouldBe false
        h.controller.state.value.wrap shouldBe true
    }

    @Test
    fun `a corrupt persistence read degrades to defaults`() = runTest {
        val h = harness()
        h.persistence.readFailure = IllegalStateException("corrupt")
        h.scope.runCurrent()

        h.controller.state.value.minSeverity shouldBe null
        h.controller.state.value.packageFilterOn shouldBe true
        h.controller.state.value.wrap shouldBe false
    }

    @Test
    fun `appended entries update visible and retained counts`() = runTest {
        val h = harness()
        h.scope.runCurrent()

        h.transport.emit(record(message = "first"))
        h.transport.emit(record(message = "second"))
        h.scope.runCurrent()

        h.controller.state.value.visibleCount shouldBe 4
        h.controller.state.value.totalRetainedCount shouldBe 4
        h.controller.snapshot().size shouldBe 4
    }

    @Test
    fun `search query filters visible entries by substring`() = runTest {
        val h = harness()
        h.scope.runCurrent()
        h.transport.emit(record(message = "a timeout occurred"))
        h.transport.emit(record(message = "all good"))
        h.scope.runCurrent()

        h.controller.handle(LogcatControlsIntent.SetQuery("timeout"))
        h.scope.runCurrent()

        h.controller.state.value.visibleCount shouldBe 1
        h.controller.snapshot().single().entry.let {
            (it as dev.acme.adbtoolbox.domain.logcat.LogcatEntry.Record).record.message.value shouldBe "a timeout occurred"
        }
    }

    @Test
    fun `minimum severity filters below the chosen level`() = runTest {
        val h = harness()
        h.scope.runCurrent()
        h.transport.emit(record(level = 'D', message = "debug line"))
        h.transport.emit(record(level = 'E', message = "error line"))
        h.scope.runCurrent()

        h.controller.handle(LogcatControlsIntent.SetMinSeverity(LogSeverity.ERROR))
        h.scope.runCurrent()

        h.controller.state.value.visibleCount shouldBe 1
        h.persistence.writes.last().minSeverity shouldBe LogSeverity.ERROR
    }

    @Test
    fun `package filter restricts to the selected package's resolved pid`() = runTest {
        val h = harness(
            initialPackage = SelectedPackageState.Selected(SelectedPackage(SERIAL_A, "com.acme.shop")),
            pidLookup = { AdbTextResult(AdbOutcome.Completed(0), "2000", "") },
        )
        h.scope.runCurrent()
        h.transport.emit(record(pid = 2000, message = "from target app"))
        h.transport.emit(record(pid = 3000, message = "from other process"))
        h.scope.runCurrent()

        h.controller.state.value.visibleCount shouldBe 1
        h.controller.state.value.packageFilterLabel shouldBe "com.acme.shop"
    }

    @Test
    fun `toggling the package filter off shows every process again`() = runTest {
        val h = harness(
            initialPackage = SelectedPackageState.Selected(SelectedPackage(SERIAL_A, "com.acme.shop")),
            pidLookup = { AdbTextResult(AdbOutcome.Completed(0), "2000", "") },
        )
        h.scope.runCurrent()
        h.transport.emit(record(pid = 2000, message = "target"))
        h.transport.emit(record(pid = 3000, message = "other"))
        h.scope.runCurrent()

        h.controller.handle(LogcatControlsIntent.TogglePackageFilter) // off
        h.scope.runCurrent()
        h.controller.state.value.visibleCount shouldBe 4

        h.controller.handle(LogcatControlsIntent.TogglePackageFilter) // on again
        h.scope.runCurrent()
        h.controller.state.value.visibleCount shouldBe 1
    }

    @Test
    fun `a selected package with no running process matches nothing while the filter is on`() = runTest {
        val h = harness(
            initialPackage = SelectedPackageState.Selected(SelectedPackage(SERIAL_A, "com.acme.shop")),
            pidLookup = { AdbTextResult(AdbOutcome.Completed(1), "", "") },
        )
        h.scope.runCurrent()
        h.transport.emit(record(pid = 3000, message = "other process"))
        h.scope.runCurrent()

        h.controller.state.value.visibleCount shouldBe 0
        h.controller.state.value.totalRetainedCount shouldBe 2
    }

    @Test
    fun `pausing freezes the view and tracks an honest unseen count`() = runTest {
        val h = harness()
        h.scope.runCurrent()
        h.transport.emit(record(message = "one"))
        h.scope.runCurrent()

        h.controller.handle(LogcatControlsIntent.TogglePause)
        h.scope.runCurrent()
        h.controller.state.value.pauseState.let { it as LogcatPauseState.Paused }

        h.transport.emit(record(message = "two"))
        h.transport.emit(record(message = "three"))
        h.scope.runCurrent()

        h.controller.state.value.unseen.paused shouldBe true
        h.controller.state.value.unseen.unseenCount shouldBe 4
    }

    @Test
    fun `resuming from pause clears the unseen count`() = runTest {
        val h = harness()
        h.scope.runCurrent()
        h.controller.handle(LogcatControlsIntent.TogglePause)
        h.transport.emit(record(message = "one"))
        h.scope.runCurrent()

        h.controller.handle(LogcatControlsIntent.TogglePause)
        h.scope.runCurrent()

        h.controller.state.value.pauseState shouldBe LogcatPauseState.Resumed
        h.controller.state.value.unseen.unseenCount shouldBe 0
    }

    @Test
    fun `manual scroll away turns follow off without pausing`() = runTest {
        val h = harness()
        h.scope.runCurrent()
        h.transport.emit(record(message = "one"))
        h.scope.runCurrent()

        h.controller.handle(LogcatControlsIntent.ManualScrollAway)
        h.transport.emit(record(message = "two"))
        h.scope.runCurrent()

        h.controller.state.value.follow shouldBe false
        h.controller.state.value.pauseState shouldBe LogcatPauseState.Resumed
        h.controller.state.value.unseen.paused shouldBe false
        h.controller.state.value.unseen.unseenCount shouldBe 2
    }

    @Test
    fun `toggling follow directly turns it back on without touching pause`() = runTest {
        val h = harness()
        h.scope.runCurrent()
        h.controller.handle(LogcatControlsIntent.TogglePause)
        h.controller.handle(LogcatControlsIntent.ToggleFollow) // off
        h.scope.runCurrent()
        h.controller.state.value.follow shouldBe false

        h.controller.handle(LogcatControlsIntent.ToggleFollow) // on again
        h.scope.runCurrent()

        h.controller.state.value.follow shouldBe true
        h.controller.state.value.pauseState.let { it as LogcatPauseState.Paused }
    }

    @Test
    fun `jump to latest resumes follow and clears pause together`() = runTest {
        val h = harness()
        h.scope.runCurrent()
        h.controller.handle(LogcatControlsIntent.TogglePause)
        h.controller.handle(LogcatControlsIntent.ManualScrollAway)
        h.transport.emit(record(message = "one"))
        h.scope.runCurrent()

        h.controller.handle(LogcatControlsIntent.JumpToLatest)
        h.scope.runCurrent()

        h.controller.state.value.follow shouldBe true
        h.controller.state.value.pauseState shouldBe LogcatPauseState.Resumed
        h.controller.state.value.unseen.unseenCount shouldBe 0
    }

    @Test
    fun `clear local resets counts without touching the device`() = runTest {
        val h = harness()
        h.scope.runCurrent()
        h.transport.emit(record(message = "one"))
        h.scope.runCurrent()

        h.controller.handle(LogcatControlsIntent.ClearLocal)
        h.scope.runCurrent()

        h.controller.state.value.totalRetainedCount shouldBe 0
        h.controller.state.value.visibleCount shouldBe 0
        h.controller.state.value.cleared shouldBe true

        h.transport.emit(record(message = "fresh"))
        h.scope.runCurrent()
        h.controller.state.value.cleared shouldBe false
    }

    @Test
    fun `reset filters clears level, package filter, and search together`() = runTest {
        val h = harness()
        h.scope.runCurrent()
        h.controller.handle(LogcatControlsIntent.SetMinSeverity(LogSeverity.ERROR))
        h.controller.handle(LogcatControlsIntent.SetQuery("timeout"))
        h.scope.runCurrent()

        h.controller.handle(LogcatControlsIntent.ResetFilters)
        h.scope.runCurrent()

        h.controller.state.value.minSeverity shouldBe null
        h.controller.state.value.packageFilterOn shouldBe false
        h.controller.state.value.query shouldBe ""
    }

    @Test
    fun `a device switch resets pause and follow but keeps persisted filter fields`() = runTest {
        val h = harness()
        h.scope.runCurrent()
        h.controller.handle(LogcatControlsIntent.SetMinSeverity(LogSeverity.ERROR))
        h.controller.handle(LogcatControlsIntent.TogglePause)
        h.controller.handle(LogcatControlsIntent.ManualScrollAway)
        h.scope.runCurrent()

        h.selectedDevice.value = online(SERIAL_B)
        h.scope.runCurrent()

        h.controller.state.value.serial shouldBe SERIAL_B
        h.controller.state.value.pauseState shouldBe LogcatPauseState.Resumed
        h.controller.state.value.follow shouldBe true
        h.controller.state.value.minSeverity shouldBe LogSeverity.ERROR
    }

    @Test
    fun `a genuine session error is surfaced`() = runTest {
        val h = harness(pidLookup = { AdbTextResult(AdbOutcome.Completed(0), "", "") })
        h.scope.runCurrent()

        h.selectedDevice.value = SelectedDeviceState.None
        h.scope.runCurrent()
        // Force a session error via a manager whose transport always fails to start.
        val failingTransport = object : AdbTransport by h.transport {
            override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> =
                kotlinx.coroutines.flow.flow {
                    emit(AdbStreamEvent.Completed(AdbOutcome.TransportFailure("device offline")))
                }
        }
        val scope = TestScope()
        val dispatcher = ControlsTestDispatchers(StandardTestDispatcher(scope.testScheduler))
        val selectedDevice = MutableStateFlow<SelectedDeviceState>(online(SERIAL_A))
        val selectedPackage = MutableStateFlow<SelectedPackageState>(SelectedPackageState.None)
        val manager = LogcatSessionManager(scope, dispatcher, selectedDevice, failingTransport)
        val pidTracker = LogcatPackagePidTracker(scope, dispatcher, selectedPackage, LogcatPidResolver(failingTransport))
        val controller = LogcatControlsController(
            scope, dispatcher, selectedDevice, selectedPackage, manager, pidTracker, FakeLogcatControlsPersistence(),
        )
        scope.runCurrent()

        controller.state.value.error shouldBe "device offline"
        controller.state.value.sessionState.let { it as LogcatSessionState.Error }
    }

    @Test
    fun `an expected stop reports no error`() = runTest {
        val h = harness()
        h.scope.runCurrent()

        h.selectedDevice.value = SelectedDeviceState.None
        h.scope.runCurrent()

        h.controller.state.value.error shouldBe null
        h.controller.state.value.sessionState.let { it as LogcatSessionState.Stopped }
            .reason shouldBe LogcatStopReason.DeviceUnavailable
    }

    @Test
    fun `eviction under a small buffer keeps the visible count and unseen honest`() = runTest {
        val h = harness(bufferCapacityBytes = 512)
        h.scope.runCurrent()

        repeat(50) { index -> h.transport.emit(record(message = "line-$index padding padding padding")) }
        h.scope.runCurrent()

        h.controller.state.value.visibleCount shouldBe h.controller.snapshot().size
        h.controller.state.value.totalRetainedCount shouldBe h.sessionManager.publication.value.entryCount
    }
}
