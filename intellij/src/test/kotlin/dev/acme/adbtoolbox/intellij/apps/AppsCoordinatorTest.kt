@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.intellij.apps

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewModel
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewState
import dev.acme.adbtoolbox.application.apps.AppsIntent
import dev.acme.adbtoolbox.application.apps.AppsViewModel
import dev.acme.adbtoolbox.application.apps.AppsViewState
import dev.acme.adbtoolbox.application.apps.ClearDataUseCase
import dev.acme.adbtoolbox.application.apps.ClearDataViewModel
import dev.acme.adbtoolbox.application.apps.ClearDataViewState
import dev.acme.adbtoolbox.application.apps.SelectedPackageViewModel
import dev.acme.adbtoolbox.application.apps.UninstallUseCase
import dev.acme.adbtoolbox.application.apps.UninstallViewModel
import dev.acme.adbtoolbox.application.apps.UninstallViewState
import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.apps.FakeSelectedPackagePersistence
import dev.acme.adbtoolbox.domain.apps.FakeClearDataConfirmationPort
import dev.acme.adbtoolbox.domain.apps.FakeUninstallConfirmationPort
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.FakeDeviceRepository
import dev.acme.adbtoolbox.domain.device.FakeDeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
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
 * Connects task 022's [AppsViewModel] and task 023's [AppLifecycleViewModel] to [AppsPanel],
 * mirroring [dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarCoordinator]'s established
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
        val feedback: dev.acme.adbtoolbox.application.feedback.FeedbackViewModel,
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
        val feedback = dev.acme.adbtoolbox.application.feedback.FeedbackViewModel(scope = scope, dispatchers = dispatchers)
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
                    dev.acme.adbtoolbox.application.apps.AppsRow("com.acme.wallet", "Wallet", true, null, false),
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
        // Mirrors dev.acme.adbtoolbox.intellij.mirroring.MirroringToggleActionTest's established
        // shape: with no eligible device selected, AppLifecycleViewModel.handle posts its "no
        // eligible device" warning synchronously — no background-dispatcher round trip needed — so
        // this proves the click reaches the exact shared view model instance without depending on
        // this headless sandbox's Dispatchers.Default scheduling latency (see
        // dev.acme.adbtoolbox.intellij.deviceactions.DeviceActionsCoordinatorTest and
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
