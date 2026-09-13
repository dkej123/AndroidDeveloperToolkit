package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.apps.UninstallConfirmation
import dev.acme.adbtoolbox.domain.apps.UninstallConfirmationPort
import dev.acme.adbtoolbox.domain.apps.UninstallResult
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.devicecontext.controlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.packages.PackageListScope
import dev.acme.adbtoolbox.domain.packages.PackageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/** Confirm-first, stale-context-safe orchestration for task 025's destructive uninstall action. */
class UninstallViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val selectedPackageState: StateFlow<SelectedPackageState>,
    private val currentPackageScope: StateFlow<PackageListScope>,
    private val uninstallUseCase: UninstallUseCase,
    private val confirmationPort: UninstallConfirmationPort,
    private val packageRepository: PackageRepository,
    private val selectedPackageViewModel: SelectedPackageViewModel,
    private val feedback: FeedbackViewModel,
    private val clock: Clock = Clock.System,
) {
    private data class Target(
        val selection: SelectedPackage,
        val deviceLabel: String,
        val packageScope: PackageListScope,
    )

    private val busyTargets = MutableStateFlow<Set<SelectedPackage>>(emptySet())
    private val _state = MutableStateFlow(reduce(selectedDeviceState.value, selectedPackageState.value, emptySet()))
    val state: StateFlow<UninstallViewState> = _state.asStateFlow()

    init {
        combine(selectedDeviceState, selectedPackageState, busyTargets, ::reduce)
            .onEach { _state.value = it }
            .launchIn(scope)
    }

    fun handle(intent: UninstallIntent) {
        when (intent) {
            UninstallIntent.Uninstall -> requestUninstall()
        }
    }

    private fun requestUninstall() {
        val device = selectedDeviceState.value
        val context = device.toCommandContext()
        if (context !is DeviceCommandContext.Eligible) {
            post(FeedbackSeverity.Warning, "No eligible device selected")
            return
        }
        val selection = selectedPackage()
            ?.takeIf { it.serial == context.serial }
        if (selection == null) {
            post(FeedbackSeverity.Warning, "No package selected")
            return
        }
        if (selection in busyTargets.value) return

        val target = Target(
            selection = selection,
            deviceLabel = (device as SelectedDeviceState.Online).device.model ?: selection.serial.value,
            packageScope = currentPackageScope.value,
        )
        busyTargets.value = busyTargets.value + selection
        scope.launch {
            try {
                val confirmation = withContext(dispatchers.main) {
                    confirmationPort.confirmUninstall(target.selection.packageName, target.deviceLabel)
                }
                // A confirmation belongs to the exact package/device snapshot it described. A
                // context switch while the modal is open invalidates it and issues zero commands.
                if (confirmation != UninstallConfirmation.Confirmed || !isCurrentTarget(target.selection)) return@launch

                when (val result = withContext(dispatchers.io) {
                    uninstallUseCase.uninstall(target.selection.serial, target.selection.packageName)
                }) {
                    UninstallResult.Success -> {
                        reconcileConfirmedRemoval(target)
                        post(FeedbackSeverity.Success, "Uninstalled ${target.selection.packageName}")
                    }
                    is UninstallResult.AlreadyMissing -> {
                        reconcileConfirmedRemoval(target)
                        post(FeedbackSeverity.Info, "${target.selection.packageName} is already uninstalled")
                    }
                    is UninstallResult.Failure -> post(FeedbackSeverity.Error, result.reason)
                    UninstallResult.TimedOut -> post(FeedbackSeverity.Error, "Uninstall timed out")
                    UninstallResult.Cancelled -> post(FeedbackSeverity.Error, "Uninstall cancelled")
                    UninstallResult.RejectedDuplicate -> Unit
                }
            } finally {
                busyTargets.value = busyTargets.value - target.selection
            }
        }
    }

    private fun reconcileConfirmedRemoval(target: Target) {
        // Never clear a package selected after the operation began.
        if (selectedPackage() == target.selection) {
            selectedPackageViewModel.handle(SelectedPackageIntent.Clear)
        }
        // PackageRepository owns one global state slot. Refreshing the old serial after a device
        // switch would overwrite/supersede discovery for the newly active device.
        val currentSerial = (selectedDeviceState.value.toCommandContext() as? DeviceCommandContext.Eligible)?.serial
        if (currentSerial == target.selection.serial) {
            packageRepository.refresh(target.selection.serial, target.packageScope)
        }
    }

    private fun isCurrentTarget(target: SelectedPackage): Boolean {
        val serial = (selectedDeviceState.value.toCommandContext() as? DeviceCommandContext.Eligible)?.serial
        return serial == target.serial && selectedPackage() == target
    }

    private fun selectedPackage(): SelectedPackage? =
        (selectedPackageState.value as? SelectedPackageState.Selected)?.selection

    private fun reduce(
        deviceState: SelectedDeviceState,
        packageState: SelectedPackageState,
        busy: Set<SelectedPackage>,
    ): UninstallViewState {
        val serial = (deviceState.toCommandContext() as? DeviceCommandContext.Eligible)?.serial
        val selection = (packageState as? SelectedPackageState.Selected)
            ?.selection
            ?.takeIf { it.serial == serial }
        return UninstallViewState(
            controlPolicy = deviceState.controlPolicy(),
            selectedPackageName = selection?.packageName,
            busy = selection != null && selection in busy,
        )
    }

    private fun post(severity: FeedbackSeverity, text: String) {
        feedback.handle(
            FeedbackIntent.Post(
                FeedbackMessage(
                    id = "uninstall-${severity.name.lowercase()}-${clock.now()}",
                    text = text,
                    severity = severity,
                ),
            ),
        )
    }
}
