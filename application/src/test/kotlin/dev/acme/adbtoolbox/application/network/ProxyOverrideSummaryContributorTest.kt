@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.network

import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummary
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private fun onlineState(serial: DeviceSerial) =
    SelectedDeviceState.Online(Device(serial = serial, state = DeviceConnectionState.Online))

/** [ProxyOverrideSummaryContributor] publishes only the current, readback-derived active endpoint (task 014/030). */
class ProxyOverrideSummaryContributorTest {

    private fun harness(stdout: String): Pair<TestScope, ProxyController> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), stdout, "") })
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val controller = ProxyController(scope, TestDispatcherProviderFixture(dispatcher), transport, selectedDeviceState)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        return scope to controller
    }

    @Test
    fun `an active proxy is reported as an override for the exact serial`() = runTest {
        val (_, controller) = harness("10.0.4.117:8888")
        val contributor = ProxyOverrideSummaryContributor(controller)

        contributor.overridesFor(serialA) shouldBe listOf(OverrideSummary("proxy", "Proxy: 10.0.4.117:8888"))
    }

    @Test
    fun `a disabled proxy reports no overrides`() = runTest {
        val (_, controller) = harness(":0")
        val contributor = ProxyOverrideSummaryContributor(controller)

        contributor.overridesFor(serialA) shouldBe emptyList()
    }

    @Test
    fun `an active proxy for a different serial reports no overrides, never mixing devices`() = runTest {
        val (_, controller) = harness("10.0.4.117:8888")
        val contributor = ProxyOverrideSummaryContributor(controller)

        contributor.overridesFor(serialB) shouldBe emptyList()
    }

    @Test
    fun `a null serial reports no overrides`() = runTest {
        val (_, controller) = harness("10.0.4.117:8888")
        val contributor = ProxyOverrideSummaryContributor(controller)

        contributor.overridesFor(null) shouldBe emptyList()
    }
}
