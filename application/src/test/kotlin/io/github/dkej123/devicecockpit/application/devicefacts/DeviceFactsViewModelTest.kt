@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.dkej123.devicecockpit.application.devicefacts

import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactValue
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbStreamEvent
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.device.Device
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactState
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactsReportFormatter
import io.github.dkej123.devicecockpit.domain.devicefacts.FakeClipboardPort
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private fun onlineDevice(serial: DeviceSerial) = Device(serial = serial, state = DeviceConnectionState.Online)

private const val ANDROID_COMMAND = "getprop ro.build.version.release ; getprop ro.build.version.sdk"

private fun wellFormedStdoutFor(commandLine: String): String = when (commandLine) {
    ANDROID_COMMAND -> "15\n35\n"
    "wm size" -> "Physical size: 1080x2400\n"
    "wm density" -> "Physical density: 420\n"
    "dumpsys battery" -> "level: 72\nscale: 100\nstatus: 2\n"
    "getprop ro.product.cpu.abi" -> "arm64-v8a\n"
    "uptime" -> " 14:32:01 up 4:12,  0 users,  load average: 0.10, 0.05, 0.01\n"
    else -> error("unexpected command: $commandLine")
}

/** A device whose battery level the test can change, recording every command it is sent. */
private class ChangingBatteryTransport(var level: Int = 72) {
    val commands = mutableListOf<String>()
    val transport = FakeAdbTransport(
        textScript = { request ->
            val commandLine = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
            commands += commandLine
            val stdout = if (commandLine == "dumpsys battery") "level: $level\nscale: 100\nstatus: 2\n" else wellFormedStdoutFor(commandLine)
            AdbTextResult(AdbOutcome.Completed(0), stdout = stdout, stderr = "")
        },
    )
}

private fun DeviceFactsViewState.battery(): Any? =
    ((this as? DeviceFactsViewState.Connected)?.snapshot?.facts?.get(DeviceFactId.Battery) as? DeviceFactState.Available)?.value

class DeviceFactsViewModelTest {

    private fun refreshingHarness(device: ChangingBatteryTransport, interval: kotlin.time.Duration?): Pair<TestScope, DeviceFactsViewModel> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val viewModel = DeviceFactsViewModel(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            selectedDeviceState = MutableStateFlow(SelectedDeviceState.Online(onlineDevice(serialA))),
            loadDeviceFacts = LoadDeviceFactsUseCase(device.transport),
            clipboard = FakeClipboardPort(),
            volatileRefreshInterval = interval,
        )
        return scope to viewModel
    }

    @Test
    fun `Refresh re-reads the facts in place without dropping back to Loading`() {
        val device = ChangingBatteryTransport(level = 72)
        val (scope, viewModel) = refreshingHarness(device, interval = null)
        scope.runCurrent()
        viewModel.state.value.battery() shouldBe DeviceFactValue.Battery(72, charging = true)

        device.level = 80
        val seen = mutableListOf<DeviceFactsViewState>()
        scope.backgroundScope.launch(StandardTestDispatcher(scope.testScheduler)) { viewModel.state.collect { seen += it } }
        viewModel.handle(DeviceFactsIntent.Refresh)
        scope.runCurrent()

        viewModel.state.value.battery() shouldBe DeviceFactValue.Battery(80, charging = true)
        seen.none { it is DeviceFactsViewState.Loading || it is DeviceFactsViewState.Partial } shouldBe true
    }

    @Test
    fun `battery and uptime are re-read periodically while a device is selected`() {
        val device = ChangingBatteryTransport(level = 72)
        val (scope, viewModel) = refreshingHarness(device, interval = 30.seconds)
        scope.runCurrent()
        device.commands.clear()

        device.level = 73
        scope.advanceTimeBy(30.seconds + 1.milliseconds)
        scope.runCurrent()

        viewModel.state.value.battery() shouldBe DeviceFactValue.Battery(73, charging = true)
        device.commands.sorted() shouldBe listOf("dumpsys battery", "uptime")
    }

    private fun harness(
        transport: FakeAdbTransport = FakeAdbTransport(
            textScript = { request ->
                val commandLine = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
                AdbTextResult(AdbOutcome.Completed(0), stdout = wellFormedStdoutFor(commandLine), stderr = "")
            },
        ),
        clipboard: FakeClipboardPort = FakeClipboardPort(),
    ): Triple<TestScope, MutableStateFlow<SelectedDeviceState>, DeviceFactsViewModel> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val viewModel = DeviceFactsViewModel(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            selectedDeviceState = selectedDeviceState,
            loadDeviceFacts = LoadDeviceFactsUseCase(transport),
            clipboard = clipboard,
        )
        return Triple(scope, selectedDeviceState, viewModel)
    }

    @Test
    fun `no selected device produces NoDevice`() = runTest {
        val (scope, selected, viewModel) = harness()
        selected.value = SelectedDeviceState.None
        scope.runCurrent()

        viewModel.state.value shouldBe DeviceFactsViewState.NoDevice
    }

    @Test
    fun `selection still loading produces Loading`() {
        val (_, _, viewModel) = harness()
        // Constructor default selectedDeviceState is SelectedDeviceState.None from harness; use a
        // fresh flow seeded with Loading to exercise this branch explicitly.
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val selected = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Loading)
        val loadingViewModel = DeviceFactsViewModel(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            selectedDeviceState = selected,
            loadDeviceFacts = LoadDeviceFactsUseCase(FakeAdbTransport()),
            clipboard = FakeClipboardPort(),
        )

        loadingViewModel.state.value shouldBe DeviceFactsViewState.Loading
    }

    @Test
    fun `an unauthorized or offline device produces NoDevice, never Connected`() = runTest {
        val (scope, selected, viewModel) = harness()
        selected.value = SelectedDeviceState.Offline(Device(serialA, DeviceConnectionState.Offline))
        scope.runCurrent()

        viewModel.state.value shouldBe DeviceFactsViewState.NoDevice
    }

    @Test
    fun `an eligible online device settles to Connected once every fact resolves`() = runTest {
        val (scope, selected, viewModel) = harness()

        selected.value = SelectedDeviceState.Online(onlineDevice(serialA))
        scope.advanceUntilIdle()

        val state = viewModel.state.value
        state.shouldBeInstanceOf<DeviceFactsViewState.Connected>()
        (state as DeviceFactsViewState.Connected).snapshot.serial shouldBe serialA
        state.snapshot.isSettled shouldBe true
    }

    @Test
    fun `one malformed fact does not blank the rest of a Connected snapshot`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request ->
                val commandLine = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
                if (commandLine == "dumpsys battery") {
                    AdbTextResult(AdbOutcome.Completed(0), stdout = "not battery output", stderr = "")
                } else {
                    AdbTextResult(AdbOutcome.Completed(0), stdout = wellFormedStdoutFor(commandLine), stderr = "")
                }
            },
        )
        val (scope, selected, viewModel) = harness(transport = transport)

        selected.value = SelectedDeviceState.Online(onlineDevice(serialA))
        scope.advanceUntilIdle()

        val state = viewModel.state.value
        state.shouldBeInstanceOf<DeviceFactsViewState.Connected>()
        val facts = (state as DeviceFactsViewState.Connected).snapshot.facts
        facts.values.filterIsInstance<DeviceFactState.Available>().size shouldBe 5
        facts.values.filterIsInstance<DeviceFactState.Unavailable>().size shouldBe 1
    }

    @Test
    fun `a device selection error surfaces as RecoverableError`() = runTest {
        val (scope, selected, viewModel) = harness()
        selected.value = SelectedDeviceState.Error("disk exploded")
        scope.runCurrent()

        viewModel.state.value shouldBe DeviceFactsViewState.RecoverableError("disk exploded")
    }

    @Test
    fun `a slow fact response for a serial the user has since switched away from never renders (stale suppression)`() =
        runTest {
            // FakeAdbTransport has no built-in per-request delay hook, so drive a transport whose
            // executeText suspends only for the serial expected to go stale.
            val slowForSerialA = object : AdbTransport {
                override suspend fun executeText(request: AdbRequest): AdbTextResult {
                    val deviceRequest = request as AdbDeviceRequest
                    val commandLine = (deviceRequest.operation as AdbOperation.Shell).command.render()
                    if (deviceRequest.serial == serialA) {
                        delay(1_000)
                    }
                    return AdbTextResult(AdbOutcome.Completed(0), stdout = wellFormedStdoutFor(commandLine), stderr = "")
                }

                override fun executeStream(request: AdbRequest): kotlinx.coroutines.flow.Flow<AdbStreamEvent> = emptyFlow()

                override suspend fun executeBinary(request: AdbRequest, sink: ByteSink) = AdbOutcome.Completed(0)
            }
            val scope = TestScope()
            val selected = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
            val viewModel = DeviceFactsViewModel(
                scope = scope,
                dispatchers = TestDispatcherProviderFixture(StandardTestDispatcher(scope.testScheduler)),
                selectedDeviceState = selected,
                loadDeviceFacts = LoadDeviceFactsUseCase(slowForSerialA),
                clipboard = FakeClipboardPort(),
            )

            selected.value = SelectedDeviceState.Online(onlineDevice(serialA))
            scope.runCurrent()
            // serialA's fetch is in flight (delayed 1s) — switch to serialB before it resolves.
            selected.value = SelectedDeviceState.Online(onlineDevice(serialB))
            scope.advanceTimeBy(1_500)
            scope.advanceUntilIdle()

            val state = viewModel.state.value
            state.shouldBeInstanceOf<DeviceFactsViewState.Connected>()
            (state as DeviceFactsViewState.Connected).snapshot.serial shouldBe serialB
        }

    @Test
    fun `CopyReport writes the formatted report for the current snapshot to the clipboard`() = runTest {
        val clipboard = FakeClipboardPort()
        val (scope, selected, viewModel) = harness(clipboard = clipboard)

        selected.value = SelectedDeviceState.Online(onlineDevice(serialA))
        scope.advanceUntilIdle()

        viewModel.handle(DeviceFactsIntent.CopyReport)
        scope.runCurrent()

        val snapshot = (viewModel.state.value as DeviceFactsViewState.Connected).snapshot
        clipboard.writes shouldBe listOf(DeviceFactsReportFormatter.format(snapshot))
    }

    @Test
    fun `CopyReport before any snapshot exists is a safe no-op`() = runTest {
        val clipboard = FakeClipboardPort()
        val (scope, _, viewModel) = harness(clipboard = clipboard)

        viewModel.handle(DeviceFactsIntent.CopyReport)
        scope.runCurrent()

        clipboard.writes shouldBe emptyList()
    }
}
