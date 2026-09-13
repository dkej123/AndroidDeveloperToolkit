package dev.acme.adbtoolbox.intellij.nav

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.FakeNavigationPersistence
import dev.acme.adbtoolbox.domain.nav.NavigationState
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import javax.swing.JLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive

/**
 * Connects task 012's [NavigationViewModel] to task 010's [AdbToolboxHostPanel] via
 * [NavigationRoutingCoordinator]. Kept as a [BasePlatformTestCase] like every other test in this
 * module. Exercises [NavigationRoutingCoordinator.route] directly against constructed
 * [NavigationState] values — the same "invoke the handler directly, don't depend on a real
 * coroutine round trip" pattern [dev.acme.adbtoolbox.intellij.host.HostPresentationSignalsTest]
 * documents, and for the same reason: this headless sandbox's coroutine round trips through
 * `:application`'s `NavigationViewModel` (`combine`/`stateIn`) are unreliable here (see
 * [dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectServiceTest]'s `Dispatchers.EDT`
 * class-doc note for the same class of gap). A separate smoke test below constructs a real
 * [NavigationRoutingCoordinator] with a real [NavigationViewModel] to exercise wiring/disposal
 * without asserting on async state propagation.
 */
class NavigationRoutingCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    private fun coordinator(
        host: AdbToolboxHostPanel,
        rail: NavigationRailPanel,
        openSettings: () -> Unit = {},
    ): NavigationRoutingCoordinator {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val viewModel = NavigationViewModel(scope, dispatchers, FakeNavigationPersistence(), ViewId.Device)
        return NavigationRoutingCoordinator(host, rail, viewModel, scope, dispatchers, openSettings)
    }

    fun `test routing to a registered view shows it in the host and syncs the rail`() {
        val host = AdbToolboxHostPanel()
        val deviceView = JLabel("device")
        host.registerFeatureView(ViewId.Device.routeKey) { deviceView }
        val rail = NavigationRailPanel()
        val coordinator = coordinator(host, rail)

        coordinator.route(NavigationState.Ready(ViewId.Device))

        assertEquals(ViewId.Device, rail.list.selectedValue)
        assertSame(deviceView, host.activeViewHost.componentFor(ViewId.Device.routeKey))

        coordinator.dispose()
    }

    fun `test routing does not recreate an already-registered view instance`() {
        val host = AdbToolboxHostPanel()
        var constructionCount = 0
        host.registerFeatureView(ViewId.Apps.routeKey) { constructionCount++; JLabel("apps") }
        val rail = NavigationRailPanel()
        val coordinator = coordinator(host, rail)
        assertEquals(1, constructionCount)

        coordinator.route(NavigationState.Ready(ViewId.Apps))
        coordinator.route(NavigationState.Ready(ViewId.Apps))

        assertEquals(1, constructionCount)

        coordinator.dispose()
    }

    fun `test routing back and forth between two registered views preserves each instance`() {
        val host = AdbToolboxHostPanel()
        val deviceView = JLabel("device")
        val appsView = JLabel("apps")
        host.registerFeatureView(ViewId.Device.routeKey) { deviceView }
        host.registerFeatureView(ViewId.Apps.routeKey) { appsView }
        val rail = NavigationRailPanel()
        val coordinator = coordinator(host, rail)

        coordinator.route(NavigationState.Ready(ViewId.Apps))
        coordinator.route(NavigationState.Ready(ViewId.Device))
        coordinator.route(NavigationState.Ready(ViewId.Apps))

        assertSame(deviceView, host.activeViewHost.componentFor(ViewId.Device.routeKey))
        assertSame(appsView, host.activeViewHost.componentFor(ViewId.Apps.routeKey))

        coordinator.dispose()
    }

    fun `test routing to an unregistered view highlights it in the rail without crashing`() {
        val host = AdbToolboxHostPanel()
        val rail = NavigationRailPanel()
        val coordinator = coordinator(host, rail)

        coordinator.route(NavigationState.Ready(ViewId.Logcat))

        assertEquals(ViewId.Logcat, rail.list.selectedValue)
        assertFalse(host.activeViewHost.isRegistered(ViewId.Logcat.routeKey))

        coordinator.dispose()
    }

    fun `test routing to Settings opens the native project Configurable without requiring a feature card`() {
        val host = AdbToolboxHostPanel()
        val rail = NavigationRailPanel()
        var opened = 0
        val coordinator = coordinator(host, rail, openSettings = { opened++ })

        coordinator.route(NavigationState.Ready(ViewId.Settings))

        assertEquals(ViewId.Settings, rail.list.selectedValue)
        assertEquals(1, opened)
        assertFalse(host.activeViewHost.isRegistered(ViewId.Settings.routeKey))
        coordinator.dispose()
    }

    fun `test routing a Loading or Error state does nothing`() {
        val host = AdbToolboxHostPanel()
        val rail = NavigationRailPanel()
        rail.setSelected(ViewId.Device)
        val coordinator = coordinator(host, rail)

        coordinator.route(NavigationState.Loading)
        coordinator.route(NavigationState.Error("boom"))

        assertEquals(ViewId.Device, rail.list.selectedValue)

        coordinator.dispose()
    }

    fun `test routing to a registered view moves focus into its content`() {
        val host = AdbToolboxHostPanel()
        var focusRequested = false
        val deviceView = object : JLabel("device") {
            override fun requestFocusInWindow(): Boolean {
                focusRequested = true
                return super.requestFocusInWindow()
            }
        }
        host.registerFeatureView(ViewId.Device.routeKey) { deviceView }
        val rail = NavigationRailPanel()
        val coordinator = coordinator(host, rail)

        coordinator.route(NavigationState.Ready(ViewId.Device))

        assertTrue("expected focus to be requested for the newly active view", focusRequested)

        coordinator.dispose()
    }

    fun `test the coordinator wires the rail's selection through to the view model without a construction-time crash`() {
        val host = AdbToolboxHostPanel()
        val rail = NavigationRailPanel()
        val coordinator = coordinator(host, rail)

        rail.list.selectedIndex = ViewId.entries.indexOf(ViewId.Network)

        coordinator.dispose()
    }

    fun `test disposing the coordinator cancels its scope`() {
        val host = AdbToolboxHostPanel()
        val rail = NavigationRailPanel()
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val viewModel = NavigationViewModel(scope, dispatchers, FakeNavigationPersistence(), ViewId.Device)
        val coordinator = NavigationRoutingCoordinator(host, rail, viewModel, scope, dispatchers)

        coordinator.dispose()

        assertFalse(scope.isActive)
    }
}
