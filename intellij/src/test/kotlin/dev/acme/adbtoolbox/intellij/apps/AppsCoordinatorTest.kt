@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.intellij.apps

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.apps.AppsIntent
import dev.acme.adbtoolbox.application.apps.AppsViewModel
import dev.acme.adbtoolbox.application.apps.AppsViewState
import dev.acme.adbtoolbox.application.apps.SelectedPackageViewModel
import dev.acme.adbtoolbox.application.device.SelectedDeviceIntent
import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.FakeSelectedPackagePersistence
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.FakeDeviceRepository
import dev.acme.adbtoolbox.domain.device.FakeDeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.packages.FakePackageRepository
import dev.acme.adbtoolbox.domain.packages.PackageEntry
import dev.acme.adbtoolbox.domain.packages.PackageListScope
import dev.acme.adbtoolbox.domain.packages.PackageListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking

private val serialA = DeviceSerial.of("AAAA111")

/**
 * Connects task 022's [AppsViewModel] to [AppsPanel], mirroring
 * [dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarCoordinator]'s established shape: [scope]
 * is owned by the caller, state is marshaled onto [dispatchers]' `main` context before touching
 * Swing, and [AppsCoordinator.render] is directly unit-testable against a constructed
 * [AppsViewState] without a real coroutine round trip.
 */
class AppsCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    private fun viewModel(dispatchers: DispatcherProvider, scope: CoroutineScope): AppsViewModel {
        val deviceRepository = FakeDeviceRepository(listOf(Device(serialA, DeviceConnectionState.Online)))
        val selectedDeviceViewModel = SelectedDeviceViewModel(
            scope = scope,
            dispatchers = dispatchers,
            deviceRepository = deviceRepository,
            persistence = FakeDeviceSelectionPersistence(),
        )
        val selectedPackageViewModel = SelectedPackageViewModel(
            scope = scope,
            dispatchers = dispatchers,
            persistence = FakeSelectedPackagePersistence(),
        )
        return AppsViewModel(
            scope = scope,
            dispatchers = dispatchers,
            packageRepository = FakePackageRepository(),
            selectedDeviceViewModel = selectedDeviceViewModel,
            selectedPackageViewModel = selectedPackageViewModel,
        )
    }

    fun `test the coordinator wires up against a real view model without a construction-time crash`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

        val coordinator = AppsCoordinator(viewModel(dispatchers, scope), scope, dispatchers)

        assertNotNull(coordinator.panel)
        coordinator.dispose()
    }

    fun `test typing in the panel's search field issues a SetQuery intent to the view model`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(dispatchers, scope)
        val coordinator = AppsCoordinator(vm, scope, dispatchers)

        coordinator.panel.searchFieldForTest.text = "shop"

        runBlocking { kotlinx.coroutines.delay(50) }
        assertEquals("shop", vm.state.value.query)
        coordinator.dispose()
    }

    fun `test clicking the system-packages toggle issues a ToggleSystemPackages intent`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(dispatchers, scope)
        val coordinator = AppsCoordinator(vm, scope, dispatchers)

        coordinator.panel.systemToggleForTest.doClick()

        runBlocking { kotlinx.coroutines.delay(50) }
        assertTrue(vm.state.value.showSystemPackages)
        coordinator.dispose()
    }

    fun `test render reflects a constructed view state onto the panel directly`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val coordinator = AppsCoordinator(viewModel(dispatchers, scope), scope, dispatchers)

        coordinator.render(
            AppsViewState(
                query = "wa",
                hasDevice = true,
                isLoading = false,
                rows = listOf(
                    dev.acme.adbtoolbox.application.apps.AppsRow("com.acme.wallet", "Wallet", true, null, false),
                ),
            ),
        )

        assertEquals("wa", coordinator.panel.searchFieldForTest.text)
        assertEquals(1, coordinator.panel.listForTest.model.size)
        coordinator.dispose()
    }

    fun `test disposing the coordinator cancels its scope and disposes the panel`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val coordinator = AppsCoordinator(viewModel(dispatchers, scope), scope, dispatchers)

        coordinator.dispose()

        assertFalse(scope.isActive)
    }
}
