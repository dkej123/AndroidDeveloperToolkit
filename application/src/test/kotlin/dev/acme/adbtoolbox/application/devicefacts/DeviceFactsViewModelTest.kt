@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.devicefacts

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactsReportFormatter
import dev.acme.adbtoolbox.domain.devicefacts.FakeClipboardPort
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.process.ByteSink
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

class DeviceFactsViewModelTest {

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
