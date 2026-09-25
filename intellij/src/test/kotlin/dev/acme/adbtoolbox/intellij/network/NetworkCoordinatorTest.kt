package dev.acme.adbtoolbox.intellij.network

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator
import dev.acme.adbtoolbox.application.network.ProxyController
import dev.acme.adbtoolbox.application.network.ProxyViewState
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.BadgeContributor
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummary
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.network.FakeHostNetworkInfo
import dev.acme.adbtoolbox.domain.network.FakeNetworkRecentsPersistence
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyHost
import dev.acme.adbtoolbox.domain.network.ProxyHostResult
import dev.acme.adbtoolbox.domain.network.ProxyPort
import dev.acme.adbtoolbox.domain.network.ProxyPortResult
import dev.acme.adbtoolbox.domain.network.ProxyReadState
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking

private val serialA = DeviceSerial.of("AAAA111")

private fun onlineState(serial: DeviceSerial) =
    SelectedDeviceState.Online(Device(serial = serial, state = DeviceConnectionState.Online))

/**
 * Connects task 032's [ProxyController] to [NetworkPanel], mirroring
 * [dev.acme.adbtoolbox.intellij.apps.AppsCoordinatorTest]'s established shape.
 */
class NetworkCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: collector renders are queued on the EDT behind the test body, so a
        // direct render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
    }

    private class Fixture(
        val scope: CoroutineScope,
        val selectedDeviceState: MutableStateFlow<SelectedDeviceState>,
        val controller: ProxyController,
        val aggregator: DeviceContextAggregator,
        val dispatchers: DispatcherProvider,
    )

    private fun fixture(
        transport: FakeAdbTransport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), ":0", "") }),
        initialState: SelectedDeviceState = SelectedDeviceState.None,
    ): Fixture {
        val dispatchers: DispatcherProvider = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val selectedDeviceState = MutableStateFlow(initialState)
        val controller = ProxyController(
            scope = scope,
            dispatchers = dispatchers,
            transport = transport,
            selectedDeviceState = selectedDeviceState,
            hostNetworkInfo = FakeHostNetworkInfo(),
            recentsPersistence = FakeNetworkRecentsPersistence(),
        )
        return Fixture(
            scope,
            selectedDeviceState,
            controller,
            DeviceContextAggregator(scope, selectedDeviceState),
            dispatchers,
        )
    }

    private fun coordinator(
        f: Fixture,
        badgeContributor: BadgeContributor? = null,
        overrideContributor: OverrideSummaryContributor? = null,
    ) = NetworkCoordinator(
        controller = f.controller,
        aggregator = f.aggregator,
        scope = f.scope,
        dispatchers = f.dispatchers,
        badgeContributor = badgeContributor ?: dev.acme.adbtoolbox.application.network.NetworkBadgeContributor(f.controller),
        overrideContributor = overrideContributor
            ?: dev.acme.adbtoolbox.application.network.ProxyOverrideSummaryContributor(f.controller),
    )

    private val activeBadgeContributor = object : BadgeContributor {
        override val viewId: ViewId = ViewId.Network
        override fun badgeFor(serial: DeviceSerial?): NavigationBadge = NavigationBadge.Attention
    }

    private val activeOverrideContributor = object : OverrideSummaryContributor {
        override fun overridesFor(serial: DeviceSerial?): List<OverrideSummary> =
            listOf(OverrideSummary(id = "proxy", description = "Proxy active"))
    }

    fun `test the coordinator wires up against a real controller without a construction-time crash`() {
        val f = fixture()

        val coordinator = coordinator(f)

        assertNotNull(coordinator.panel)
        coordinator.dispose()
    }

    fun `test typing in the host field issues an EditHost intent to the controller`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.panel.hostFieldForTest.text = "10.0.4.117"

        runBlocking { kotlinx.coroutines.delay(50) }
        assertEquals("10.0.4.117", f.controller.state.value.hostInput)
        coordinator.dispose()
    }

    fun `test typing in the port field issues an EditPort intent to the controller`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.panel.portFieldForTest.text = "8888"

        runBlocking { kotlinx.coroutines.delay(50) }
        assertEquals("8888", f.controller.state.value.portInput)
        coordinator.dispose()
    }

    fun `test render reflects a constructed view state onto the panel directly`() {
        val f = fixture()
        val coordinator = coordinator(f)
        val endpoint = ProxyEndpoint(
            (ProxyHost.parse("10.0.4.117") as ProxyHostResult.Valid).host,
            (ProxyPort.parse(8888) as ProxyPortResult.Valid).port,
        )

        coordinator.render(ProxyViewState(readState = ProxyReadState.Active(endpoint), hostInput = "10.0.4.117", portInput = "8888"))

        assertEquals("10.0.4.117", coordinator.panel.hostFieldForTest.text)
        assertEquals("8888", coordinator.panel.portFieldForTest.text)
        assertTrue(coordinator.panel.activeBannerLabelForTest.isVisible)
        assertEquals("Disable", coordinator.panel.enableButtonForTest.text)
        coordinator.dispose()
    }

    fun `test device-mutating controls stay disabled when the selected device is ineligible`() {
        val f = fixture()
        val coordinator = coordinator(f)
        val endpoint = ProxyEndpoint(
            (ProxyHost.parse("10.0.4.117") as ProxyHostResult.Valid).host,
            (ProxyPort.parse(8888) as ProxyPortResult.Valid).port,
        )

        coordinator.render(
            ProxyViewState(
                serial = serialA,
                readState = ProxyReadState.Active(endpoint),
                isDeviceEligible = false,
                hostInput = "10.0.4.117",
                portInput = "8888",
            ),
        )

        assertFalse(coordinator.panel.enableButtonForTest.isEnabled)
        assertFalse(coordinator.panel.resetLinkForTest.isEnabled)
        coordinator.dispose()
    }

    fun `test clicking Enable with no eligible device surfaces an error, issuing no command`() {
        val f = fixture()
        val coordinator = coordinator(f)
        coordinator.render(ProxyViewState(hostInput = "10.0.4.117", portInput = "8888"))
        coordinator.panel.enableButtonForTest.isEnabled = true

        coordinator.panel.enableButtonForTest.doClick()

        runBlocking { kotlinx.coroutines.delay(50) }
        assertEquals("No device selected", f.controller.state.value.error)
        coordinator.dispose()
    }

    fun `test selecting a recent row forwards SelectRecent, filling fields without enabling`() {
        val f = fixture()
        val coordinator = coordinator(f)
        val endpoint = ProxyEndpoint(
            (ProxyHost.parse("10.0.4.117") as ProxyHostResult.Valid).host,
            (ProxyPort.parse(8888) as ProxyPortResult.Valid).port,
        )
        coordinator.render(ProxyViewState(recents = listOf(endpoint)))
        coordinator.panel.recentsListForTest.selectedIndex = 0

        runBlocking { kotlinx.coroutines.delay(50) }
        assertEquals("10.0.4.117", f.controller.state.value.hostInput)
        assertEquals("8888", f.controller.state.value.portInput)
        coordinator.dispose()
    }

    fun `test active readback contributes Network badge and proxy override`() {
        val f = fixture()
        val coordinator = coordinator(f, activeBadgeContributor, activeOverrideContributor)

        coordinator.render(f.controller.state.value)

        assertEquals(NavigationBadge.Attention, f.aggregator.state.value.badges[ViewId.Network])
        assertEquals("proxy", f.aggregator.state.value.overrides.single().id)
        coordinator.dispose()
    }

    fun `test disposing unregisters Network badge and proxy override contributors`() {
        val f = fixture()
        val coordinator = coordinator(f, activeBadgeContributor, activeOverrideContributor)
        coordinator.render(f.controller.state.value)
        assertEquals(NavigationBadge.Attention, f.aggregator.state.value.badges[ViewId.Network])
        assertEquals("proxy", f.aggregator.state.value.overrides.single().id)

        coordinator.dispose()

        assertTrue(f.aggregator.state.value.badges.isEmpty())
        assertTrue(f.aggregator.state.value.overrides.isEmpty())
    }

    fun `test disposing the coordinator cancels its scope`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.dispose()

        assertFalse(f.scope.isActive)
    }
}
