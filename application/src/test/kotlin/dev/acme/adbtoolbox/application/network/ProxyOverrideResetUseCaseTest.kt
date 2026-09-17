@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.network

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetOutcome
import dev.acme.adbtoolbox.domain.network.FakeHostNetworkInfo
import dev.acme.adbtoolbox.domain.network.FakeNetworkRecentsPersistence
import dev.acme.adbtoolbox.domain.network.ProxyReadState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private fun onlineState(serial: DeviceSerial) =
    SelectedDeviceState.Online(Device(serial = serial, state = DeviceConnectionState.Online))

private fun completedText(stdout: String, exitCode: Int? = 0) =
    AdbTextResult(AdbOutcome.Completed(exitCode), stdout = stdout, stderr = "")

/** [ProxyController]'s task 041 [dev.acme.adbtoolbox.domain.devicecontext.OverrideResetUseCase] surface. */
class ProxyOverrideResetUseCaseTest {

    private fun harness(transport: AdbTransport): Triple<TestScope, MutableStateFlow<SelectedDeviceState>, ProxyController> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val controller = ProxyController(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            transport = transport,
            selectedDeviceState = selectedDeviceState,
            hostNetworkInfo = FakeHostNetworkInfo(),
            recentsPersistence = FakeNetworkRecentsPersistence(),
        )
        return Triple(scope, selectedDeviceState, controller)
    }

    @Test
    fun `featureId is proxy`() = runCurrentSetup { _, _, controller ->
        controller.featureId shouldBe "proxy"
    }

    @Test
    fun `currentValue reflects the active endpoint for the exact serial, or null otherwise`() = runCurrentSetup { _, _, controller ->
        controller.currentValue(serialA) shouldBe "10.0.4.117:8888"
        controller.currentValue(serialB) shouldBe null
    }

    @Test
    fun `reset writes the disabled endpoint and reports success from the readback`() {
        val responses = mutableListOf("10.0.4.117:8888", "ignored-put-response", ":0")
        val transport = FakeAdbTransport(textScript = { completedText(responses.removeFirst()) })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        var outcome: OverrideResetOutcome? = null
        scope.launch { outcome = controller.reset(serialA) }
        scope.runCurrent()

        outcome shouldBe OverrideResetOutcome.Success
        controller.state.value.readState shouldBe ProxyReadState.Disabled
    }

    @Test
    fun `reset for a serial other than the currently tracked one fails without issuing a command`() {
        val transport = FakeAdbTransport(textScript = { completedText("10.0.4.117:8888") })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        val requestsBefore = transport.textRequests.size

        var outcome: OverrideResetOutcome? = null
        scope.launch { outcome = controller.reset(serialB) }
        scope.runCurrent()

        outcome.shouldBeInstanceOf<OverrideResetOutcome.Failed>()
        transport.textRequests.size shouldBe requestsBefore
    }

    @Test
    fun `reapply enables the stored endpoint and reports success from the readback`() {
        val responses = mutableListOf(":0", "ignored-put-response", "10.0.4.117:8888")
        val transport = FakeAdbTransport(textScript = { completedText(responses.removeFirst()) })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        var outcome: OverrideResetOutcome? = null
        scope.launch { outcome = controller.reapply(serialA, "10.0.4.117:8888") }
        scope.runCurrent()

        outcome shouldBe OverrideResetOutcome.Success
        val putRequest = transport.textRequests[1] as AdbDeviceRequest
        (putRequest.operation as AdbOperation.Shell).command.render() shouldBe
            "settings put global http_proxy '10.0.4.117:8888'"
    }

    @Test
    fun `reapply rejects a malformed stored endpoint without issuing a command`() {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        val requestsBefore = transport.textRequests.size

        var outcome: OverrideResetOutcome? = null
        scope.launch { outcome = controller.reapply(serialA, "not-an-endpoint") }
        scope.runCurrent()

        outcome.shouldBeInstanceOf<OverrideResetOutcome.Failed>()
        transport.textRequests.size shouldBe requestsBefore
    }

    private fun runCurrentSetup(block: (TestScope, MutableStateFlow<SelectedDeviceState>, ProxyController) -> Unit) {
        val transport = FakeAdbTransport(textScript = { completedText("10.0.4.117:8888") })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        block(scope, selectedDeviceState, controller)
    }
}
