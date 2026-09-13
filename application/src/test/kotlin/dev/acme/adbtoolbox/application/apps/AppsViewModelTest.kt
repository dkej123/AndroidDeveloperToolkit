@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.FakeSelectedPackagePersistence
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.FakeDeviceRepository
import dev.acme.adbtoolbox.domain.device.FakeDeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.packages.FakePackageRepository
import dev.acme.adbtoolbox.domain.packages.PackageEntry
import dev.acme.adbtoolbox.domain.packages.PackageListScope
import dev.acme.adbtoolbox.domain.packages.PackageListState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class AppsTestDispatcherProviderFixture(dispatcher: kotlinx.coroutines.CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private fun entry(name: String, label: String = name, debuggable: Boolean? = null) =
    PackageEntry(packageName = name, label = label, labelResolved = label != name, isDebuggable = debuggable)

private class Harness(
    val scope: TestScope,
    val deviceRepository: FakeDeviceRepository,
    val packageRepository: FakePackageRepository,
    val selectedDeviceViewModel: SelectedDeviceViewModel,
    val selectedPackageViewModel: SelectedPackageViewModel,
    val viewModel: AppsViewModel,
) {
    fun settle() {
        scope.advanceTimeBy(1)
        scope.runCurrent()
    }
}

private fun harness(
    devices: List<Device> = listOf(Device(serialA, DeviceConnectionState.Online)),
    packageState: PackageListState = PackageListState.Loading,
    selectedPackagePersistence: FakeSelectedPackagePersistence = FakeSelectedPackagePersistence(),
): Harness {
    val scope = TestScope()
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val dispatchers = AppsTestDispatcherProviderFixture(dispatcher)
    val deviceRepository = FakeDeviceRepository(devices)
    val packageRepository = FakePackageRepository(packageState)
    val selectedDeviceViewModel = SelectedDeviceViewModel(
        scope = scope,
        dispatchers = dispatchers,
        deviceRepository = deviceRepository,
        persistence = FakeDeviceSelectionPersistence(),
    )
    val selectedPackageViewModel = SelectedPackageViewModel(
        scope = scope,
        dispatchers = dispatchers,
        persistence = selectedPackagePersistence,
    )
    val viewModel = AppsViewModel(
        scope = scope,
        dispatchers = dispatchers,
        packageRepository = packageRepository,
        selectedDeviceViewModel = selectedDeviceViewModel,
        selectedPackageViewModel = selectedPackageViewModel,
    )
    return Harness(scope, deviceRepository, packageRepository, selectedDeviceViewModel, selectedPackageViewModel, viewModel)
}

class AppsViewModelTest {

    @Test
    fun `with no device selected, hasDevice is false and rows are empty`() = runTest {
        val h = harness(devices = emptyList())
        h.settle()

        h.viewModel.state.value.hasDevice shouldBe false
        h.viewModel.state.value.rows shouldBe emptyList()
    }

    @Test
    fun `selecting a device triggers a User-scope refresh by default`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()

        h.packageRepository.refreshRequests.last() shouldBe (serialA to PackageListScope.User)
    }

    @Test
    fun `content for the selected device populates rows sorted as provided`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()

        h.packageRepository.emit(
            PackageListState.Content(serialA, PackageListScope.User, listOf(entry("com.acme.shop", "Shop"))),
        )
        h.settle()

        val state = h.viewModel.state.value
        state.isLoading shouldBe false
        state.rows shouldBe listOf(AppsRow("com.acme.shop", "Shop", true, null, isSelected = false))
    }

    @Test
    fun `search matches by label case-insensitively`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()
        h.packageRepository.emit(
            PackageListState.Content(
                serialA,
                PackageListScope.User,
                listOf(entry("com.acme.shop", "Shop"), entry("com.acme.wallet", "Wallet")),
            ),
        )
        h.settle()

        h.viewModel.handle(AppsIntent.SetQuery("SHOP"))
        h.settle()

        h.viewModel.state.value.rows.map { it.packageName } shouldBe listOf("com.acme.shop")
    }

    @Test
    fun `search matches by package name case-insensitively`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()
        h.packageRepository.emit(
            PackageListState.Content(
                serialA,
                PackageListScope.User,
                listOf(entry("com.acme.shop", "Shop"), entry("com.acme.wallet", "Wallet")),
            ),
        )
        h.settle()

        h.viewModel.handle(AppsIntent.SetQuery("WALLET"))
        h.settle()

        h.viewModel.state.value.rows.map { it.packageName } shouldBe listOf("com.acme.wallet")
    }

    @Test
    fun `an empty query matches every package`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()
        h.packageRepository.emit(
            PackageListState.Content(
                serialA,
                PackageListScope.User,
                listOf(entry("com.acme.shop", "Shop"), entry("com.acme.wallet", "Wallet")),
            ),
        )
        h.settle()

        h.viewModel.handle(AppsIntent.SetQuery(""))
        h.settle()

        h.viewModel.state.value.rows.map { it.packageName } shouldBe listOf("com.acme.shop", "com.acme.wallet")
    }

    @Test
    fun `a non-blank query matching nothing produces isFilteredEmpty true`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()
        h.packageRepository.emit(
            PackageListState.Content(serialA, PackageListScope.User, listOf(entry("com.acme.shop", "Shop"))),
        )
        h.settle()

        h.viewModel.handle(AppsIntent.SetQuery("nomatch"))
        h.settle()

        val state = h.viewModel.state.value
        state.rows shouldBe emptyList()
        state.isFilteredEmpty shouldBe true
        state.isGenuinelyEmpty shouldBe false
    }

    @Test
    fun `ClearFilter resets the query back to empty`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()
        h.packageRepository.emit(
            PackageListState.Content(serialA, PackageListScope.User, listOf(entry("com.acme.shop", "Shop"))),
        )
        h.settle()
        h.viewModel.handle(AppsIntent.SetQuery("nomatch"))
        h.settle()

        h.viewModel.handle(AppsIntent.ClearFilter)
        h.settle()

        h.viewModel.state.value.query shouldBe ""
        h.viewModel.state.value.rows.map { it.packageName } shouldBe listOf("com.acme.shop")
    }

    @Test
    fun `toggling system packages requests the All scope and re-requests User when toggled back`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()

        h.viewModel.handle(AppsIntent.ToggleSystemPackages)
        h.viewModel.currentPackageScope.value shouldBe PackageListScope.All
        h.settle()
        h.packageRepository.refreshRequests.last() shouldBe (serialA to PackageListScope.All)
        h.viewModel.state.value.showSystemPackages shouldBe true

        h.viewModel.handle(AppsIntent.ToggleSystemPackages)
        h.viewModel.currentPackageScope.value shouldBe PackageListScope.User
        h.settle()
        h.packageRepository.refreshRequests.last() shouldBe (serialA to PackageListScope.User)
        h.viewModel.state.value.showSystemPackages shouldBe false
    }

    @Test
    fun `selecting a package updates state and the shared selected-package contract`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()
        h.packageRepository.emit(
            PackageListState.Content(serialA, PackageListScope.User, listOf(entry("com.acme.shop", "Shop"))),
        )
        h.settle()

        h.viewModel.handle(AppsIntent.SelectPackage("com.acme.shop"))
        h.settle()

        h.viewModel.state.value.selectedPackageName shouldBe "com.acme.shop"
        h.viewModel.state.value.rows.single().isSelected shouldBe true
        h.selectedPackageViewModel.state.value shouldBe
            dev.acme.adbtoolbox.domain.apps.SelectedPackageState.Selected(SelectedPackage(serialA, "com.acme.shop"))
    }

    @Test
    fun `deselecting a package clears state and the shared contract`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()
        h.packageRepository.emit(
            PackageListState.Content(serialA, PackageListScope.User, listOf(entry("com.acme.shop", "Shop"))),
        )
        h.settle()
        h.viewModel.handle(AppsIntent.SelectPackage("com.acme.shop"))
        h.settle()

        h.viewModel.handle(AppsIntent.SelectPackage(null))
        h.settle()

        h.viewModel.state.value.selectedPackageName shouldBe null
    }

    @Test
    fun `switching to a different device never shows the previous device's selection`() = runTest {
        val h = harness(devices = listOf(Device(serialA, DeviceConnectionState.Online), Device(serialB, DeviceConnectionState.Online)))
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()
        h.packageRepository.emit(
            PackageListState.Content(serialA, PackageListScope.User, listOf(entry("com.acme.shop", "Shop"))),
        )
        h.settle()
        h.viewModel.handle(AppsIntent.SelectPackage("com.acme.shop"))
        h.settle()

        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialB))
        h.settle()

        h.viewModel.state.value.selectedPackageName shouldBe null
    }

    @Test
    fun `a stale package list keyed to a different serial than the active device is treated as loading`() = runTest {
        val h = harness(devices = listOf(Device(serialA, DeviceConnectionState.Online), Device(serialB, DeviceConnectionState.Online)))
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()
        h.packageRepository.emit(
            PackageListState.Content(serialA, PackageListScope.User, listOf(entry("com.acme.shop", "Shop"))),
        )
        h.settle()

        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialB))
        h.settle()
        // packageRepository has NOT emitted fresh content for serialB yet — still holds serialA's Content.

        val state = h.viewModel.state.value
        state.hasDevice shouldBe true
        state.isLoading shouldBe true
        state.rows shouldBe emptyList()
    }

    @Test
    fun `restoring a persisted selection whose package no longer exists clears it (revalidation)`() = runTest {
        val persistence = FakeSelectedPackagePersistence(initial = SelectedPackage(serialA, "com.acme.gone"))
        val h = harness(selectedPackagePersistence = persistence)
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()

        h.packageRepository.emit(
            PackageListState.Content(serialA, PackageListScope.User, listOf(entry("com.acme.shop", "Shop"))),
        )
        h.settle()

        h.viewModel.state.value.selectedPackageName shouldBe null
        h.selectedPackageViewModel.state.value shouldBe dev.acme.adbtoolbox.domain.apps.SelectedPackageState.None
    }

    @Test
    fun `restoring a persisted selection whose package still exists keeps it selected (restart survival)`() = runTest {
        val persistence = FakeSelectedPackagePersistence(initial = SelectedPackage(serialA, "com.acme.shop"))
        val h = harness(selectedPackagePersistence = persistence)
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()

        h.packageRepository.emit(
            PackageListState.Content(serialA, PackageListScope.User, listOf(entry("com.acme.shop", "Shop"))),
        )
        h.settle()

        h.viewModel.state.value.selectedPackageName shouldBe "com.acme.shop"
    }

    @Test
    fun `a corrupt or malformed persisted selection degrades to no selection rather than crashing`() = runTest {
        val persistence = FakeSelectedPackagePersistence().apply { readFailure = RuntimeException("corrupt xml") }
        val h = harness(selectedPackagePersistence = persistence)
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()

        h.packageRepository.emit(
            PackageListState.Content(serialA, PackageListScope.User, listOf(entry("com.acme.shop", "Shop"))),
        )
        h.settle()

        h.viewModel.state.value.selectedPackageName shouldBe null
    }

    @Test
    fun `an error package list state surfaces errorMessage and empty rows`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()

        h.packageRepository.emit(PackageListState.Error(serialA, PackageListScope.User, "adb: device offline"))
        h.settle()

        val state = h.viewModel.state.value
        state.errorMessage shouldBe "adb: device offline"
        state.rows shouldBe emptyList()
        state.isLoading shouldBe false
    }

    @Test
    fun `Refresh intent re-requests the current serial and scope`() = runTest {
        val h = harness()
        h.settle()
        h.selectedDeviceViewModel.handle(dev.acme.adbtoolbox.application.device.SelectedDeviceIntent.Select(serialA))
        h.settle()

        h.viewModel.handle(AppsIntent.Refresh)
        h.settle()

        h.packageRepository.refreshRequests.last() shouldBe (serialA to PackageListScope.User)
    }
}
