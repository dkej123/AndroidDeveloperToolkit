@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.apps.ClearDataCommands
import dev.acme.adbtoolbox.domain.apps.ClearDataConfirmation
import dev.acme.adbtoolbox.domain.apps.FakeClearDataConfirmationPort
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.packages.FakePackageRepository
import dev.acme.adbtoolbox.domain.packages.PackageEntry
import dev.acme.adbtoolbox.domain.packages.PackageListScope
import dev.acme.adbtoolbox.domain.packages.PackageListState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private class ClearDataTestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val FIXED_CLOCK = object : kotlinx.datetime.Clock {
    override fun now(): Instant = Instant.parse("2026-09-12T10:15:30Z")
}

private val SERIAL = DeviceSerial.of("emulator-5554")
private const val PACKAGE = "com.acme.shop"

private fun onlineDevice(serial: DeviceSerial, model: String? = "Pixel 8 Pro") =
    Device(serial = serial, state = DeviceConnectionState.Online, model = model)

/**
 * Covers task 024's presenter-level TDD plan: no command runs before explicit confirmation,
 * confirming issues exactly one `pm clear` for the exact target, cancelling issues zero commands,
 * a duplicate submit while a request is already awaiting confirmation/running is ignored, a
 * selection/device change *after* the confirmation dialog was already asked never redirects the
 * eventual command to the new (stale) target, and a successful clear refreshes the affected
 * package's state via [dev.acme.adbtoolbox.domain.packages.PackageRepository].
 */
class ClearDataViewModelTest {

    private class Harness(
        textScript: (AdbRequest) -> AdbTextResult = { AdbTextResult(AdbOutcome.Completed(0), "Success", "") },
        confirmation: ClearDataConfirmation = ClearDataConfirmation.Confirmed,
    ) {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = ClearDataTestDispatcherProviderFixture(dispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val selectedPackageState = MutableStateFlow<SelectedPackageState>(SelectedPackageState.None)
        val currentPackageScope = MutableStateFlow(PackageListScope.User)
        val transport = FakeAdbTransport(textScript = textScript)
        val useCase = ClearDataUseCase(transport)
        val confirmationPort = FakeClearDataConfirmationPort(confirmation)
        val packageRepository = FakePackageRepository()
        val feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers)
        val viewModel = ClearDataViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            selectedPackageState = selectedPackageState,
            currentPackageScope = currentPackageScope,
            clearDataUseCase = useCase,
            confirmationPort = confirmationPort,
            packageRepository = packageRepository,
            feedback = feedback,
            clock = FIXED_CLOCK,
        )

        fun selectOnlineDeviceAndPackage(serial: DeviceSerial = SERIAL, packageName: String = PACKAGE) {
            selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(serial))
            selectedPackageState.value = SelectedPackageState.Selected(SelectedPackage(serial, packageName))
        }
    }

    @Test
    fun `initial state mirrors the current device control policy and package selection`() = runTest {
        val h = Harness()
        h.selectOnlineDeviceAndPackage()
        h.scope.runCurrent()

        h.viewModel.state.value.controlPolicy shouldBe ControlPolicy.Enabled
        h.viewModel.state.value.selectedPackageName shouldBe PACKAGE
    }

    @Test
    fun `requesting clear-data with no eligible device posts a warning and asks no confirmation`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.None

        h.viewModel.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        h.confirmationPort.invocations shouldBe emptyList()
        h.transport.textRequests shouldBe emptyList()
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Warning
        h.feedback.state.value.toasts.single().text shouldBe "No eligible device selected"
    }

    @Test
    fun `requesting clear-data with an eligible device but no selected package posts a warning`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(SERIAL))
        h.selectedPackageState.value = SelectedPackageState.None

        h.viewModel.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        h.confirmationPort.invocations shouldBe emptyList()
        h.transport.textRequests shouldBe emptyList()
        h.feedback.state.value.toasts.single().text shouldBe "No package selected"
    }

    @Test
    fun `a selection belonging to a different device is never targeted`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(SERIAL))
        h.selectedPackageState.value = SelectedPackageState.Selected(SelectedPackage(DeviceSerial.of("other-serial"), PACKAGE))

        h.viewModel.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        h.confirmationPort.invocations shouldBe emptyList()
        h.transport.textRequests shouldBe emptyList()
    }

    @Test
    fun `confirming asks the confirmation port with the exact package and device label before issuing any command`() = runTest {
        val h = Harness()
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        h.confirmationPort.invocations.single().packageName shouldBe PACKAGE
        h.confirmationPort.invocations.single().deviceLabel shouldBe "Pixel 8 Pro"
        val request = h.transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        request.operation shouldBe AdbOperation.Shell(ClearDataCommands.clear(PACKAGE))
    }

    @Test
    fun `confirming executes clear-data exactly once and posts a success toast`() = runTest {
        val h = Harness()
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        h.transport.textRequests.size shouldBe 1
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Success
        h.viewModel.state.value.busy shouldBe false
    }

    @Test
    fun `cancelling issues zero commands and posts no toast`() = runTest {
        val h = Harness(confirmation = ClearDataConfirmation.Cancelled)
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        h.transport.textRequests shouldBe emptyList()
        h.feedback.state.value.toasts shouldBe emptyList()
        h.viewModel.state.value.busy shouldBe false
    }

    @Test
    fun `a failed clear-data posts an error toast preserving the underlying reason`() = runTest {
        val h = Harness(textScript = { AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "") })
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Error
        toast.text shouldBe "device offline"
    }

    @Test
    fun `a successful clear-data refreshes the affected package's state via the package repository`() = runTest {
        val h = Harness()
        h.currentPackageScope.value = PackageListScope.All
        h.packageRepository.emit(
            PackageListState.Content(SERIAL, PackageListScope.All, listOf(PackageEntry.unresolved(PACKAGE))),
        )
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        h.packageRepository.refreshRequests shouldBe listOf(SERIAL to PackageListScope.All)
    }

    @Test
    fun `a successful clear-data uses the active All scope while repository state is still Loading`() = runTest {
        val h = Harness()
        h.currentPackageScope.value = PackageListScope.All
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        h.packageRepository.refreshRequests shouldBe listOf(SERIAL to PackageListScope.All)
    }

    @Test
    fun `a failed clear-data never refreshes the package repository`() = runTest {
        val h = Harness(textScript = { AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "") })
        h.selectOnlineDeviceAndPackage()

        h.viewModel.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        h.packageRepository.refreshRequests shouldBe emptyList()
    }

    @Test
    fun `a duplicate submit while a confirmation is already awaited is ignored`() = runTest {
        val gate = CompletableDeferred<ClearDataConfirmation>()
        val h = Harness()
        val gatedPort = GatedClearDataConfirmationPort(gate)
        val vm = ClearDataViewModel(
            scope = h.scope,
            dispatchers = h.dispatchers,
            selectedDeviceState = h.selectedDeviceState,
            selectedPackageState = h.selectedPackageState,
            currentPackageScope = h.currentPackageScope,
            clearDataUseCase = h.useCase,
            confirmationPort = gatedPort,
            packageRepository = h.packageRepository,
            feedback = h.feedback,
            clock = FIXED_CLOCK,
        )
        h.selectOnlineDeviceAndPackage()

        vm.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()
        vm.state.value.busy shouldBe true

        vm.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()
        gatedPort.callCount shouldBe 1

        gate.complete(ClearDataConfirmation.Confirmed)
        h.scope.runCurrent()
        h.transport.textRequests.size shouldBe 1
        vm.state.value.busy shouldBe false
    }

    @Test
    fun `a package or device change while confirmation is pending never redirects the eventual command`() = runTest {
        val gate = CompletableDeferred<ClearDataConfirmation>()
        val h = Harness()
        val gatedPort = GatedClearDataConfirmationPort(gate)
        val vm = ClearDataViewModel(
            scope = h.scope,
            dispatchers = h.dispatchers,
            selectedDeviceState = h.selectedDeviceState,
            selectedPackageState = h.selectedPackageState,
            currentPackageScope = h.currentPackageScope,
            clearDataUseCase = h.useCase,
            confirmationPort = gatedPort,
            packageRepository = h.packageRepository,
            feedback = h.feedback,
            clock = FIXED_CLOCK,
        )
        h.selectOnlineDeviceAndPackage()

        vm.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()

        // The user switches to a different package (and, separately, a different device is now
        // active) while the confirmation dialog for the ORIGINAL target is still open.
        h.selectedPackageState.value = SelectedPackageState.Selected(SelectedPackage(SERIAL, "com.acme.other"))
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice(DeviceSerial.of("other-serial")))
        h.scope.runCurrent()

        gate.complete(ClearDataConfirmation.Confirmed)
        h.scope.runCurrent()

        val request = h.transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        request.operation shouldBe AdbOperation.Shell(ClearDataCommands.clear(PACKAGE))
    }

    @Test
    fun `an in-flight clear-data for one package never marks a different selected package busy`() = runTest {
        val gate = CompletableDeferred<ClearDataConfirmation>()
        val h = Harness()
        val gatedPort = GatedClearDataConfirmationPort(gate)
        val vm = ClearDataViewModel(
            scope = h.scope,
            dispatchers = h.dispatchers,
            selectedDeviceState = h.selectedDeviceState,
            selectedPackageState = h.selectedPackageState,
            currentPackageScope = h.currentPackageScope,
            clearDataUseCase = h.useCase,
            confirmationPort = gatedPort,
            packageRepository = h.packageRepository,
            feedback = h.feedback,
            clock = FIXED_CLOCK,
        )
        h.selectOnlineDeviceAndPackage()
        vm.handle(ClearDataIntent.ClearData)
        h.scope.runCurrent()
        vm.state.value.busy shouldBe true

        h.selectedPackageState.value = SelectedPackageState.Selected(SelectedPackage(SERIAL, "com.acme.other"))
        h.scope.runCurrent()

        vm.state.value.busy shouldBe false
        gate.complete(ClearDataConfirmation.Confirmed)
        h.scope.runCurrent()
    }
}

/** A [dev.acme.adbtoolbox.domain.apps.ClearDataConfirmationPort] whose call suspends on [gate] until released. */
private class GatedClearDataConfirmationPort(
    private val gate: CompletableDeferred<ClearDataConfirmation>,
) : dev.acme.adbtoolbox.domain.apps.ClearDataConfirmationPort {
    var callCount = 0
        private set

    override suspend fun confirmClearData(packageName: String, deviceLabel: String): ClearDataConfirmation {
        callCount++
        return gate.await()
    }
}
