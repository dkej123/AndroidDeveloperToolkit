@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.network

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.network.NetworkThrottle
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

private class Dispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

/** A fake emulator console: remembers the speed/delay profiles it was given. */
private class FakeEmulatorConsole {
    var speed = "full"
    var delay = "none"
    var refuse = false
    private val speeds = mapOf("full" to 0L, "lte" to 173_000_000L, "hsdpa" to 13_980_000L, "umts" to 384_000L, "edge" to 473_600L, "gprs" to 57_600L)
    private val delays = mapOf("none" to 0, "umts" to 200, "edge" to 400, "gprs" to 200)

    val transport = FakeAdbTransport(textScript = { request ->
        val args = ((request as AdbDeviceRequest).operation as AdbOperation.Host).arguments
        val reply = when (args.getOrNull(2)) {
            "speed" -> if (refuse) "KO: busy" else { speed = args[3]; "OK" }
            "delay" -> { delay = args[3]; "OK" }
            else -> "Current network status:\n  download speed: ${speeds[speed]} bits/s\n  maximum latency: ${delays[delay]} ms\nOK"
        }
        AdbTextResult(AdbOutcome.Completed(0), reply, "")
    })
}

private fun online(serial: String) = SelectedDeviceState.Online(Device(DeviceSerial.of(serial), DeviceConnectionState.Online))

class NetworkThrottleViewModelTest {

    private fun harness(device: SelectedDeviceState, console: FakeEmulatorConsole = FakeEmulatorConsole()): Triple<TestScope, NetworkThrottleViewModel, FakeEmulatorConsole> {
        val scope = TestScope()
        val viewModel = NetworkThrottleViewModel(scope, Dispatchers(StandardTestDispatcher(scope.testScheduler)), console.transport, MutableStateFlow(device))
        return Triple(scope, viewModel, console)
    }

    @Test
    fun `an emulator reads its current throttle and applies a preset`() {
        val (scope, viewModel, console) = harness(online("emulator-5554"))
        scope.runCurrent()
        viewModel.state.value shouldBe NetworkThrottleViewState(ThrottleAvailability.Emulator, current = NetworkThrottle.Off)

        viewModel.handle(NetworkThrottleIntent.Apply(NetworkThrottle.Edge))
        scope.runCurrent()

        (console.speed to console.delay) shouldBe ("edge" to "edge")
        viewModel.state.value.current shouldBe NetworkThrottle.Edge
        viewModel.state.value.busy shouldBe false
    }

    @Test
    fun `a physical device is reported as unsupported and never sent console commands`() {
        val (scope, viewModel, console) = harness(online("R58N90ABCDE"))
        scope.runCurrent()

        viewModel.handle(NetworkThrottleIntent.Apply(NetworkThrottle.Edge))
        scope.runCurrent()

        viewModel.state.value.availability shouldBe ThrottleAvailability.PhysicalDevice
        console.transport.textRequests shouldBe emptyList()
    }

    @Test
    fun `a refused write is reported while the readback still shows the real state`() {
        val console = FakeEmulatorConsole().apply { refuse = true }
        val (scope, viewModel, _) = harness(online("emulator-5554"), console)
        scope.runCurrent()

        viewModel.handle(NetworkThrottleIntent.Apply(NetworkThrottle.Gprs))
        scope.runCurrent()

        viewModel.state.value.current shouldBe NetworkThrottle.Off
        viewModel.state.value.error shouldBe "Emulator refused the throttle: KO: busy"
    }
}
