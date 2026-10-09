@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.dkej123.devicecockpit.intellij.apps

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.apps.AppLifecycleUseCase
import io.github.dkej123.devicecockpit.application.apps.AppLifecycleViewModel
import io.github.dkej123.devicecockpit.application.apps.AppLifecycleViewState
import io.github.dkej123.devicecockpit.application.apps.AppsIntent
import io.github.dkej123.devicecockpit.application.apps.AppsViewModel
import io.github.dkej123.devicecockpit.application.apps.AppsViewState
import io.github.dkej123.devicecockpit.application.apps.ClearDataUseCase
import io.github.dkej123.devicecockpit.application.apps.ClearDataViewModel
import io.github.dkej123.devicecockpit.application.apps.ClearDataViewState
import io.github.dkej123.devicecockpit.application.apps.SelectedPackageViewModel
import io.github.dkej123.devicecockpit.application.apps.UninstallUseCase
import io.github.dkej123.devicecockpit.application.apps.UninstallViewModel
import io.github.dkej123.devicecockpit.application.apps.UninstallViewState
import io.github.dkej123.devicecockpit.application.device.SelectedDeviceViewModel
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.apps.FakeSelectedPackagePersistence
import io.github.dkej123.devicecockpit.domain.apps.FakeClearDataConfirmationPort
import io.github.dkej123.devicecockpit.domain.apps.FakeUninstallConfirmationPort
import io.github.dkej123.devicecockpit.domain.apps.SelectedPackage
import io.github.dkej123.devicecockpit.domain.device.Device
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.FakeDeviceRepository
import io.github.dkej123.devicecockpit.domain.device.FakeDeviceSelectionPersistence
import io.github.dkej123.devicecockpit.domain.devicecontext.ControlPolicy
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.packages.FakePackageRepository
import io.github.dkej123.devicecockpit.domain.packages.PackageEntry
import io.github.dkej123.devicecockpit.domain.packages.PackageListScope
import io.github.dkej123.devicecockpit.domain.packages.PackageListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking

private val serialA = DeviceSerial.of("AAAA111")

/**
 * Connects task 022's [AppsViewModel] and task 023's [AppLifecycleViewModel] to [AppsPanel],
 * mirroring [io.github.dkej123.devicecockpit.intellij.devicebar.DeviceContextBarCoordinator]'s established
 * shape: [scope] is owned by the caller, state is marshaled onto [dispatchers]' `main` context
 * before touching Swing, and [AppsCoordinator.render]/[AppsCoordinator.renderLifecycle] are
 * directly unit-testable against constructed view-state values without a real coroutine round trip.
 */
class AppsCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: collector renders are queued on the EDT behind the test body, so a
        // direct render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
    }

    private class Fixture(
        val dispatchers: DispatcherProvider,
        val scope: CoroutineScope,
        val selectedDeviceViewModel: SelectedDeviceViewModel,
        val selectedPackageViewModel: SelectedPackageViewModel,
        val appsViewModel: AppsViewModel,
        val appLifecycleViewModel: AppLifecycleViewModel,
        val clearDataViewModel: ClearDataViewModel,
        val uninstallViewModel: UninstallViewModel,
        val feedback: io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel,
    )

    private fun fixture(
        dispatchers: DispatcherProvider = TestDispatchers(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
        transport: FakeAdbTransport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "", "") }),
    ): Fixture {
        // Two devices and no persisted selection: nothing is selected. With a single online device
        // SelectedDeviceViewModel would auto-select it, racing these "no eligible device" tests.
        val deviceRepository = FakeDeviceRepository(
            listOf(
                Device(serialA, DeviceConnectionState.Online),
                Device(DeviceSerial.of("SECOND01"), DeviceConnectionState.Online),
            ),
        )
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
        val packageRepository = FakePackageRepository()
        val appsViewModel = AppsViewModel(
            scope = scope,
            dispatchers = dispatchers,
            packageRepository = packageRepository,
            selectedDeviceViewModel = selectedDeviceViewModel,
            selectedPackageViewModel = selectedPackageViewModel,
        )
        val feedback = io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel(scope = scope, dispatchers = dispatchers)
        val appLifecycleViewModel = AppLifecycleViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceViewModel.state,
            selectedPackageState = selectedPackageViewModel.state,
            appLifecycleUseCase = AppLifecycleUseCase(transport),
            feedback = feedback,
        )
        val clearDataViewModel = ClearDataViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceViewModel.state,
            selectedPackageState = selectedPackageViewModel.state,
            currentPackageScope = appsViewModel.currentPackageScope,
            clearDataUseCase = ClearDataUseCase(transport),
            confirmationPort = FakeClearDataConfirmationPort(),
            packageRepository = packageRepository,
            feedback = feedback,
        )
        val uninstallViewModel = UninstallViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceViewModel.state,
            selectedPackageState = selectedPackageViewModel.state,
            currentPackageScope = appsViewModel.currentPackageScope,
            uninstallUseCase = UninstallUseCase(transport),
            confirmationPort = FakeUninstallConfirmationPort(),
            packageRepository = packageRepository,
            selectedPackageViewModel = selectedPackageViewModel,
            feedback = feedback,
        )
        return Fixture(
            dispatchers,
            scope,
            selectedDeviceViewModel,
            selectedPackageViewModel,
            appsViewModel,
            appLifecycleViewModel,
            clearDataViewModel,
            uninstallViewModel,
            feedback,
        )
    }

    private fun coordinator(f: Fixture) = AppsCoordinator(
        viewModel = f.appsViewModel,
        appLifecycleViewModel = f.appLifecycleViewModel,
        clearDataViewModel = f.clearDataViewModel,
        uninstallViewModel = f.uninstallViewModel,
        scope = f.scope,
        dispatchers = f.dispatchers,
    )

    fun `test the coordinator wires up against real view models without a construction-time crash`() {
        val f = fixture()

        val coordinator = coordinator(f)

        assertNotNull(coordinator.panel)
        coordinator.dispose()
    }

    fun `test typing in the panel's search field issues a SetQuery intent to the view model`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.panel.searchFieldForTest.text = "shop"

        runBlocking { kotlinx.coroutines.delay(50) }
        assertEquals("shop", f.appsViewModel.state.value.query)
        coordinator.dispose()
    }

    fun `test clicking the system-packages toggle issues a ToggleSystemPackages intent`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.panel.systemToggleForTest.doClick()

        runBlocking { kotlinx.coroutines.delay(50) }
        assertTrue(f.appsViewModel.state.value.showSystemPackages)
        coordinator.dispose()
    }

    fun `test render reflects a constructed view state onto the panel directly`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.render(
            AppsViewState(
                query = "wa",
                hasDevice = true,
                isLoading = false,
                rows = listOf(
                    io.github.dkej123.devicecockpit.application.apps.AppsRow("com.acme.wallet", "Wallet", true, null, false),
                ),
            ),
        )

        assertEquals("wa", coordinator.panel.searchFieldForTest.text)
        assertEquals(1, coordinator.panel.listForTest.model.size)
        coordinator.dispose()
    }

    fun `test renderLifecycle reflects a constructed lifecycle view state onto the panel directly`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.renderLifecycle(
            AppLifecycleViewState(controlPolicy = ControlPolicy.Enabled, selectedPackageName = "com.acme.wallet", busy = false),
        )

        assertTrue(coordinator.panel.restartButtonForTest.isEnabled)
        assertTrue(coordinator.panel.forceStopButtonForTest.isEnabled)
        assertTrue(coordinator.panel.launchButtonForTest.isEnabled)
        coordinator.dispose()
    }

    fun `test clicking restart in the panel forwards Restart to the shared app lifecycle view model`() {
        // Mirrors io.github.dkej123.devicecockpit.intellij.mirroring.MirroringToggleActionTest's established
        // shape: with no eligible device selected, AppLifecycleViewModel.handle posts its "no
        // eligible device" warning synchronously — no background-dispatcher round trip needed — so
        // this proves the click reaches the exact shared view model instance without depending on
        // this headless sandbox's Dispatchers.Default scheduling latency (see
        // io.github.dkej123.devicecockpit.intellij.deviceactions.DeviceActionsCoordinatorTest and
        // AdbToolboxProjectServiceTest's own documented dispatcher-timing caveats, neither of which
        // asserts on a coordinator's own background state-collection job completing).
        val f = fixture()
        val coordinator = coordinator(f)
        // Swing's doClick() is a no-op on a disabled button, so enable it directly via the
        // already-proven-synchronous renderLifecycle seam (see the dedicated renderLifecycle test
        // above) rather than the coordinator's own background state-collection job.
        coordinator.renderLifecycle(AppLifecycleViewState(controlPolicy = ControlPolicy.Enabled, selectedPackageName = "com.acme.wallet", busy = false))
        val before = f.feedback.state.value.toasts.size

        coordinator.panel.restartButtonForTest.doClick()

        val toasts = f.feedback.state.value.toasts
        assertEquals(before + 1, toasts.size)
        assertEquals("No eligible device selected", toasts.last().text)
        coordinator.dispose()
    }

    fun `test disposing the coordinator cancels its scope and disposes the panel`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.dispose()

        assertFalse(f.scope.isActive)
    }

    fun `test renderClearData reflects the destructive action state`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.renderClearData(
            ClearDataViewState(ControlPolicy.Enabled, selectedPackageName = "com.acme.shop", busy = false),
        )

        assertTrue(coordinator.panel.clearDataButtonForTest.isEnabled)
        coordinator.dispose()
    }

    fun `test clicking Clear data forwards to the shared clear-data view model`() {
        val f = fixture()
        val coordinator = coordinator(f)
        coordinator.renderClearData(
            ClearDataViewState(ControlPolicy.Enabled, selectedPackageName = "com.acme.shop", busy = false),
        )
        val before = f.feedback.state.value.toasts.size

        coordinator.panel.clearDataButtonForTest.doClick()

        assertEquals(before + 1, f.feedback.state.value.toasts.size)
        assertEquals("No eligible device selected", f.feedback.state.value.toasts.last().text)
        coordinator.dispose()
    }

    fun `test renderUninstall reflects the destructive action state`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.renderUninstall(
            UninstallViewState(ControlPolicy.Enabled, selectedPackageName = "com.acme.shop", busy = false),
        )

        assertTrue(coordinator.panel.uninstallButtonForTest.isEnabled)
        coordinator.dispose()
    }

    fun `test clicking Uninstall forwards to the shared uninstall view model`() {
        val f = fixture()
        val coordinator = coordinator(f)
        coordinator.renderUninstall(
            UninstallViewState(ControlPolicy.Enabled, selectedPackageName = "com.acme.shop", busy = false),
        )
        val before = f.feedback.state.value.toasts.size

        coordinator.panel.uninstallButtonForTest.doClick()

        assertEquals(before + 1, f.feedback.state.value.toasts.size)
        assertEquals("No eligible device selected", f.feedback.state.value.toasts.last().text)
        coordinator.dispose()
    }
}
