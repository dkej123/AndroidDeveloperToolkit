@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.network

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.domain.network.FakeHostNetworkInfo
import dev.acme.adbtoolbox.domain.network.FakeNetworkRecentsPersistence
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

/** [NetworkBadgeContributor] surfaces the Network rail badge only for the exact active serial (task 032/014). */
class NetworkBadgeContributorTest {

    private fun harness(stdout: String): Pair<TestScope, ProxyController> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), stdout, "") })
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val controller = ProxyController(
            scope,
            TestDispatcherProviderFixture(dispatcher),
            transport,
            selectedDeviceState,
            FakeHostNetworkInfo(),
            FakeNetworkRecentsPersistence(),
        )
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        return scope to controller
    }

    @Test
    fun `an active proxy shows an attention badge on the exact serial`() = runTest {
        val (_, controller) = harness("10.0.4.117:8888")
        val contributor = NetworkBadgeContributor(controller)

        contributor.viewId shouldBe ViewId.Network
        contributor.badgeFor(serialA) shouldBe NavigationBadge.Attention
    }

    @Test
    fun `a disabled proxy shows no badge`() = runTest {
        val (_, controller) = harness(":0")
        val contributor = NetworkBadgeContributor(controller)

        contributor.badgeFor(serialA) shouldBe NavigationBadge.None
    }

    @Test
    fun `an active proxy for a different serial shows no badge, never mixing devices`() = runTest {
        val (_, controller) = harness("10.0.4.117:8888")
        val contributor = NetworkBadgeContributor(controller)

        contributor.badgeFor(serialB) shouldBe NavigationBadge.None
    }

    @Test
    fun `a null serial shows no badge`() = runTest {
        val (_, controller) = harness("10.0.4.117:8888")
        val contributor = NetworkBadgeContributor(controller)

        contributor.badgeFor(null) shouldBe NavigationBadge.None
    }
}
