@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.dkej123.devicecockpit.application.apps

import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbStreamEvent
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.apps.FakeSelectedPackagePersistence
import io.github.dkej123.devicecockpit.domain.apps.FakeUninstallConfirmationPort
import io.github.dkej123.devicecockpit.domain.apps.SelectedPackage
import io.github.dkej123.devicecockpit.domain.apps.SelectedPackageState
import io.github.dkej123.devicecockpit.domain.apps.UninstallConfirmation
import io.github.dkej123.devicecockpit.domain.apps.UninstallConfirmationPort
import io.github.dkej123.devicecockpit.domain.device.Device
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.devicecontext.ControlPolicy
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackSeverity
import io.github.dkej123.devicecockpit.domain.packages.FakePackageRepository
import io.github.dkej123.devicecockpit.domain.packages.PackageListScope
import io.github.dkej123.devicecockpit.domain.packages.PackageListState
import io.github.dkej123.devicecockpit.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private class UninstallDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val UNINSTALL_VM_SERIAL = DeviceSerial.of("emulator-5554")
private val OTHER_UNINSTALL_VM_SERIAL = DeviceSerial.of("emulator-5556")
private const val UNINSTALL_VM_PACKAGE = "com.acme.shop"
private val UNINSTALL_CLOCK = object : kotlinx.datetime.Clock {
    override fun now(): Instant = Instant.parse("2026-09-13T12:00:00Z")
}

class UninstallViewModelTest {
    private class Harness(
        transport: AdbTransport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(0), "Success", "") },
        ),
        confirmationPort: UninstallConfirmationPort = FakeUninstallConfirmationPort(),
    ) {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = UninstallDispatchers(dispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val persistence = FakeSelectedPackagePersistence()
        val selectedPackageViewModel = SelectedPackageViewModel(scope, dispatchers, persistence)
        val packageScope = MutableStateFlow(PackageListScope.User)
        val packageRepository = FakePackageRepository()
        val feedback = FeedbackViewModel(scope, dispatchers)
        val uninstallUseCase = UninstallUseCase(transport)
        val viewModel = UninstallViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            selectedPackageState = selectedPackageViewModel.state,
            currentPackageScope = packageScope,
            uninstallUseCase = uninstallUseCase,
            confirmationPort = confirmationPort,
            packageRepository = packageRepository,
            selectedPackageViewModel = selectedPackageViewModel,
            feedback = feedback,
            clock = UNINSTALL_CLOCK,
        )

        fun select(serial: DeviceSerial = UNINSTALL_VM_SERIAL, packageName: String = UNINSTALL_VM_PACKAGE) {
            selectedDeviceState.value = SelectedDeviceState.Online(
                Device(serial, DeviceConnectionState.Online, model = "Pixel 8 Pro"),
            )
            scope.runCurrent()
            selectedPackageViewModel.handle(SelectedPackageIntent.Select(serial, packageName))
            scope.runCurrent()
        }
    }

    @Test
    fun `state enables uninstall only for an online selected target`() = runTest {
        val h = Harness()
        h.select()

        h.viewModel.state.value shouldBe UninstallViewState(
            controlPolicy = ControlPolicy.Enabled,
            selectedPackageName = UNINSTALL_VM_PACKAGE,
            busy = false,
        )
    }

    @Test
    fun `missing eligible device or matching package asks no confirmation and executes no command`() = runTest {
        val confirmation = FakeUninstallConfirmationPort()
        val transport = FakeAdbTransport(textScript = { error("must not execute") })
        val noDevice = Harness(transport, confirmation)

        noDevice.viewModel.handle(UninstallIntent.Uninstall)
        noDevice.scope.runCurrent()

        confirmation.invocations shouldBe emptyList()
        transport.textRequests shouldBe emptyList()
        noDevice.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Warning

        val noPackage = Harness(transport, confirmation)
        noPackage.selectedDeviceState.value = SelectedDeviceState.Online(
            Device(UNINSTALL_VM_SERIAL, DeviceConnectionState.Online, model = "Pixel 8 Pro"),
        )
        noPackage.scope.runCurrent()
        noPackage.viewModel.handle(UninstallIntent.Uninstall)
        noPackage.scope.runCurrent()

        confirmation.invocations shouldBe emptyList()
        transport.textRequests shouldBe emptyList()
    }

    @Test
    fun `cancel confirmation executes no command and changes no selection`() = runTest {
        val transport = FakeAdbTransport(textScript = { error("must not execute") })
        val h = Harness(transport, FakeUninstallConfirmationPort(UninstallConfirmation.Cancelled))
        h.select()

        h.viewModel.handle(UninstallIntent.Uninstall)
        h.scope.runCurrent()

        transport.textRequests shouldBe emptyList()
        h.selectedPackageViewModel.state.value shouldBe
            SelectedPackageState.Selected(SelectedPackage(UNINSTALL_VM_SERIAL, UNINSTALL_VM_PACKAGE))
        h.packageRepository.refreshRequests shouldBe emptyList()
    }

    @Test
    fun `device or package switch while confirmation is open makes it stale and executes zero commands`() = runTest {
        val gate = CompletableDeferred<UninstallConfirmation>()
        val confirmation = GatedUninstallConfirmation(gate)
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "Success", "") })
        val h = Harness(transport, confirmation)
        h.select()
        h.viewModel.handle(UninstallIntent.Uninstall)
        h.scope.runCurrent()

        h.selectedDeviceState.value = SelectedDeviceState.Online(
            Device(OTHER_UNINSTALL_VM_SERIAL, DeviceConnectionState.Online, model = "Other"),
        )
        h.selectedPackageViewModel.handle(SelectedPackageIntent.Select(OTHER_UNINSTALL_VM_SERIAL, "com.acme.other"))
        h.scope.runCurrent()
        gate.complete(UninstallConfirmation.Confirmed)
        h.scope.runCurrent()

        transport.textRequests shouldBe emptyList()
        h.packageRepository.refreshRequests shouldBe emptyList()
    }

    @Test
    fun `duplicate submit while confirmation is pending asks once and executes once`() = runTest {
        val gate = CompletableDeferred<UninstallConfirmation>()
        val confirmation = CountingGatedUninstallConfirmation(gate)
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(0), "Success", "") },
        )
        val h = Harness(transport, confirmation)
        h.select()

        h.viewModel.handle(UninstallIntent.Uninstall)
        h.scope.runCurrent()
        h.viewModel.handle(UninstallIntent.Uninstall)
        h.scope.runCurrent()
        confirmation.callCount shouldBe 1

        gate.complete(UninstallConfirmation.Confirmed)
        h.scope.runCurrent()
        transport.textRequests.size shouldBe 1
    }

    @Test
    fun `successful uninstall clears only the exact current selection and refreshes its scope`() = runTest {
        val h = Harness()
        h.packageScope.value = PackageListScope.All
        h.select()

        h.viewModel.handle(UninstallIntent.Uninstall)
        h.scope.runCurrent()

        h.selectedPackageViewModel.state.value shouldBe SelectedPackageState.None
        h.packageRepository.refreshRequests shouldBe listOf(UNINSTALL_VM_SERIAL to PackageListScope.All)
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Success
    }

    @Test
    fun `already missing package is reconciled without reporting uninstall failure`() = runTest {
        val h = Harness(
            transport = FakeAdbTransport(
                textScript = {
                    AdbTextResult(AdbOutcome.Completed(1), "Failure [not installed for 0]", "")
                },
            ),
        )
        h.select()

        h.viewModel.handle(UninstallIntent.Uninstall)
        h.scope.runCurrent()

        h.selectedPackageViewModel.state.value shouldBe SelectedPackageState.None
        h.packageRepository.refreshRequests shouldBe listOf(UNINSTALL_VM_SERIAL to PackageListScope.User)
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Info
    }

    @Test
    fun `refresh failure after command success remains partial success and selection stays reconciled`() = runTest {
        val h = Harness()
        h.select()

        h.viewModel.handle(UninstallIntent.Uninstall)
        h.scope.runCurrent()
        h.packageRepository.emit(
            PackageListState.Error(UNINSTALL_VM_SERIAL, PackageListScope.User, "package refresh timed out"),
        )
        h.scope.runCurrent()

        h.selectedPackageViewModel.state.value shouldBe SelectedPackageState.None
        h.packageRepository.state.value shouldBe
            PackageListState.Error(UNINSTALL_VM_SERIAL, PackageListScope.User, "package refresh timed out")
    }

    @Test
    fun `failed timeout and cancelled outcomes never optimistically clear or refresh`() = runTest {
        listOf<AdbOutcome>(
            AdbOutcome.Completed(1),
            AdbOutcome.TimedOut,
            AdbOutcome.Cancelled,
        ).forEach { outcome ->
            val h = Harness(
                transport = FakeAdbTransport(
                    textScript = { AdbTextResult(outcome, "Failure [DELETE_FAILED_INTERNAL_ERROR]", "") },
                ),
            )
            h.select()

            h.viewModel.handle(UninstallIntent.Uninstall)
            h.scope.runCurrent()

            h.selectedPackageViewModel.state.value shouldBe
                SelectedPackageState.Selected(SelectedPackage(UNINSTALL_VM_SERIAL, UNINSTALL_VM_PACKAGE))
            h.packageRepository.refreshRequests shouldBe emptyList()
            h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Error
        }
    }

    @Test
    fun `in-flight uninstall does not clear the row before the command reports success`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(GatedSuccessfulUninstallTransport(gate))
        h.select()

        h.viewModel.handle(UninstallIntent.Uninstall)
        h.scope.runCurrent()

        h.viewModel.state.value.busy shouldBe true
        h.selectedPackageViewModel.state.value shouldBe
            SelectedPackageState.Selected(SelectedPackage(UNINSTALL_VM_SERIAL, UNINSTALL_VM_PACKAGE))
        h.packageRepository.refreshRequests shouldBe emptyList()

        gate.complete(Unit)
        h.scope.runCurrent()
        h.selectedPackageViewModel.state.value shouldBe SelectedPackageState.None
    }

    @Test
    fun `device switch while uninstall command is in flight preserves new selection and skips stale refresh`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(GatedSuccessfulUninstallTransport(gate))
        h.select()
        h.viewModel.handle(UninstallIntent.Uninstall)
        h.scope.runCurrent()

        h.selectedDeviceState.value = SelectedDeviceState.Online(
            Device(OTHER_UNINSTALL_VM_SERIAL, DeviceConnectionState.Online, model = "Other"),
        )
        h.selectedPackageViewModel.handle(SelectedPackageIntent.Select(OTHER_UNINSTALL_VM_SERIAL, "com.acme.other"))
        h.scope.runCurrent()
        gate.complete(Unit)
        h.scope.runCurrent()

        h.selectedPackageViewModel.state.value shouldBe
            SelectedPackageState.Selected(SelectedPackage(OTHER_UNINSTALL_VM_SERIAL, "com.acme.other"))
        h.packageRepository.refreshRequests shouldBe emptyList()
    }
}

private class GatedUninstallConfirmation(
    private val gate: CompletableDeferred<UninstallConfirmation>,
) : UninstallConfirmationPort {
    override suspend fun confirmUninstall(packageName: String, deviceLabel: String): UninstallConfirmation = gate.await()
}

private class CountingGatedUninstallConfirmation(
    private val gate: CompletableDeferred<UninstallConfirmation>,
) : UninstallConfirmationPort {
    var callCount: Int = 0
        private set

    override suspend fun confirmUninstall(packageName: String, deviceLabel: String): UninstallConfirmation {
        callCount++
        return gate.await()
    }
}

private class GatedSuccessfulUninstallTransport(private val gate: CompletableDeferred<Unit>) : AdbTransport {
    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        gate.await()
        return AdbTextResult(AdbOutcome.Completed(0), "Success", "")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = emptyFlow()
    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}
