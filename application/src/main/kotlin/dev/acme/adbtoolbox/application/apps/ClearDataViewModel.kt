package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.apps.ClearDataConfirmation
import dev.acme.adbtoolbox.domain.apps.ClearDataConfirmationPort
import dev.acme.adbtoolbox.domain.apps.ClearDataResult
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
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

/**
 * Owns task 024's confirm-before-command invariant. The target and current package-list scope are
 * snapshotted before the confirmation is shown, so a later selection change can neither redirect
 * the destructive command nor refresh a different device.
 */
class ClearDataViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val selectedPackageState: StateFlow<SelectedPackageState>,
    private val currentPackageScope: StateFlow<PackageListScope>,
    private val clearDataUseCase: ClearDataUseCase,
    private val confirmationPort: ClearDataConfirmationPort,
    private val packageRepository: PackageRepository,
    private val feedback: FeedbackViewModel,
    private val clock: Clock = Clock.System,
) {
    private data class Target(
        val selection: SelectedPackage,
        val deviceLabel: String,
        val packageScope: PackageListScope,
    )

    private val busyTargets = MutableStateFlow<Set<SelectedPackage>>(emptySet())
    private val _state = MutableStateFlow(
        reduce(selectedDeviceState.value, selectedPackageState.value, busyTargets.value),
    )
    val state: StateFlow<ClearDataViewState> = _state.asStateFlow()

    init {
        combine(selectedDeviceState, selectedPackageState, busyTargets, ::reduce)
            .onEach { _state.value = it }
            .launchIn(scope)
    }

    fun handle(intent: ClearDataIntent) {
        when (intent) {
            ClearDataIntent.ClearData -> requestClearData()
        }
    }

    private fun requestClearData() {
        val device = selectedDeviceState.value
        val context = device.toCommandContext()
        if (context !is DeviceCommandContext.Eligible) {
            post(FeedbackSeverity.Warning, "No eligible device selected")
            return
        }
        val selection = (selectedPackageState.value as? SelectedPackageState.Selected)
            ?.selection
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
                    confirmationPort.confirmClearData(target.selection.packageName, target.deviceLabel)
                }
                if (confirmation == ClearDataConfirmation.Confirmed) {
                    val result = withContext(dispatchers.io) {
                        clearDataUseCase.clearData(target.selection.serial, target.selection.packageName)
                    }
                    when (result) {
                        ClearDataResult.Success -> {
                            packageRepository.refresh(target.selection.serial, target.packageScope)
                            post(FeedbackSeverity.Success, "Cleared data for ${target.selection.packageName}")
                        }
                        is ClearDataResult.Failure -> post(FeedbackSeverity.Error, result.reason)
                        ClearDataResult.RejectedDuplicate -> Unit
                    }
                }
            } finally {
                busyTargets.value = busyTargets.value - target.selection
            }
        }
    }

    private fun post(severity: FeedbackSeverity, text: String) {
        feedback.handle(
            FeedbackIntent.Post(
                FeedbackMessage(
                    id = "clear-data-${severity.name.lowercase()}-${clock.now()}",
                    text = text,
                    severity = severity,
                ),
            ),
        )
    }

    private fun reduce(
        deviceState: SelectedDeviceState,
        packageState: SelectedPackageState,
        busy: Set<SelectedPackage>,
    ): ClearDataViewState {
        val serial = (deviceState.toCommandContext() as? DeviceCommandContext.Eligible)?.serial
        val selection = (packageState as? SelectedPackageState.Selected)
            ?.selection
            ?.takeIf { it.serial == serial }
        return ClearDataViewState(
            controlPolicy = deviceState.controlPolicy(),
            selectedPackageName = selection?.packageName,
            busy = selection != null && selection in busy,
        )
    }
}
