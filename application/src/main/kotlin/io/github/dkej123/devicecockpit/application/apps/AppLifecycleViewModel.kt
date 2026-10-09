package io.github.dkej123.devicecockpit.application.apps

import io.github.dkej123.devicecockpit.application.feedback.FeedbackIntent
import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.apps.AppLifecycleResult
import io.github.dkej123.devicecockpit.domain.apps.AppRestartResult
import io.github.dkej123.devicecockpit.domain.apps.SelectedPackage
import io.github.dkej123.devicecockpit.domain.apps.SelectedPackageState
import io.github.dkej123.devicecockpit.domain.device.DeviceCommandContext
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.device.toCommandContext
import io.github.dkej123.devicecockpit.domain.devicecontext.controlPolicy
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackMessage
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackSeverity
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
 * The feature ViewModel for task 023's minimal Apps-view Force-stop/Launch/Restart binding (ADR
 * 0004's MVI seam): both [io.github.dkej123.devicecockpit.intellij.apps.AppsPanel]'s action-footer buttons and
 * the global restart action/shortcut forward the exact same [AppLifecycleIntent.Restart] to this
 * one shared instance's [handle] — never two independent restart code paths.
 *
 * The action target — `(serial, packageName)` — is always resolved fresh from [selectedDeviceState]
 * and [selectedPackageState] at the moment an intent is handled (never cached), and
 * [selectedPackageState] only ever contributes a package name when its selection's serial matches
 * the *currently active* device (mirrors [AppsViewModel]'s cross-device-safety rule) — a leftover
 * selection belonging to a different device can never be targeted.
 *
 * **Duplicate-in-flight prevention** is keyed to the exact `(serial, packageName)` pair via
 * [busyKeys], matching [AppLifecycleUseCase]'s own guard (defense in depth: the use case would
 * reject a genuine race anyway, but this early-out also avoids dispatching a doomed call and keeps
 * [AppLifecycleViewState.busy] accurate for the *currently selected* package only — a different
 * package's own in-flight action never marks this one busy).
 */
class AppLifecycleViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val selectedPackageState: StateFlow<SelectedPackageState>,
    private val appLifecycleUseCase: AppLifecycleUseCase,
    private val feedback: FeedbackViewModel,
    private val clock: Clock = Clock.System,
) {
    private val busyKeys = MutableStateFlow<Set<String>>(emptySet())

    private val _state = MutableStateFlow(
        reduce(selectedDeviceState.value, selectedPackageState.value, busyKeys.value),
    )
    val state: StateFlow<AppLifecycleViewState> = _state.asStateFlow()

    init {
        combine(selectedDeviceState, selectedPackageState, busyKeys, ::reduce)
            .onEach { _state.value = it }
            .launchIn(scope)
    }

    fun handle(intent: AppLifecycleIntent) {
        val context = selectedDeviceState.value.toCommandContext()
        if (context !is DeviceCommandContext.Eligible) {
            postWarning("No eligible device selected")
            return
        }
        val packageName = selectedPackageNameFor(context.serial)
        if (packageName == null) {
            postWarning("No package selected")
            return
        }
        val target = SelectedPackage(context.serial, packageName)
        val key = key(target.serial, target.packageName)
        if (key in busyKeys.value) return // duplicate in-flight for this exact target: silently ignored

        busyKeys.value = busyKeys.value + key
        scope.launch {
            when (intent) {
                AppLifecycleIntent.ForceStop -> {
                    val result = withContext(dispatchers.io) { appLifecycleUseCase.forceStop(target.serial, target.packageName) }
                    busyKeys.value = busyKeys.value - key
                    postLifecycleResult(result, successText = "Force-stopped ${target.packageName}")
                }
                AppLifecycleIntent.Launch -> {
                    val result = withContext(dispatchers.io) { appLifecycleUseCase.launch(target.serial, target.packageName) }
                    busyKeys.value = busyKeys.value - key
                    postLifecycleResult(result, successText = "Launched ${target.packageName}")
                }
                AppLifecycleIntent.Restart -> {
                    val result = withContext(dispatchers.io) { appLifecycleUseCase.restart(target.serial, target.packageName) }
                    busyKeys.value = busyKeys.value - key
                    postRestartResult(result, packageName = target.packageName)
                }
            }
        }
    }

    private fun postLifecycleResult(result: AppLifecycleResult, successText: String) {
        when (result) {
            AppLifecycleResult.Success -> postSuccess(successText)
            is AppLifecycleResult.Failure -> postError(result.reason)
            AppLifecycleResult.RejectedDuplicate -> Unit // another in-flight call already owns this target
        }
    }

    private fun postRestartResult(result: AppRestartResult, packageName: String) {
        when (result) {
            AppRestartResult.Success -> postSuccess("Restarted $packageName")
            is AppRestartResult.ForceStopFailed -> postError(result.reason)
            // Explicit, distinct wording — restart must never read as a plain success when the
            // launch step failed after a successful force-stop (task 023's acceptance criterion).
            is AppRestartResult.LaunchFailedAfterForceStop ->
                postError("Force-stopped $packageName, but launch failed: ${result.reason}")
            AppRestartResult.RejectedDuplicate -> Unit
        }
    }

    private fun postWarning(text: String) = feedback.handle(
        FeedbackIntent.Post(FeedbackMessage(id = "app-lifecycle-warning-${clock.now()}", text = text, severity = FeedbackSeverity.Warning)),
    )

    private fun postSuccess(text: String) = feedback.handle(
        FeedbackIntent.Post(FeedbackMessage(id = "app-lifecycle-success-${clock.now()}", text = text, severity = FeedbackSeverity.Success)),
    )

    private fun postError(text: String) = feedback.handle(
        FeedbackIntent.Post(FeedbackMessage(id = "app-lifecycle-error-${clock.now()}", text = text, severity = FeedbackSeverity.Error)),
    )

    private fun selectedPackageNameFor(serial: DeviceSerial): String? =
        (selectedPackageState.value as? SelectedPackageState.Selected)
            ?.selection
            ?.takeIf { it.serial == serial }
            ?.packageName

    private fun key(serial: DeviceSerial, packageName: String): String = "$serial|$packageName"

    private fun reduce(
        deviceState: SelectedDeviceState,
        packageState: SelectedPackageState,
        busy: Set<String>,
    ): AppLifecycleViewState {
        val serial = serialOf(deviceState)
        val selectedPackageName = (packageState as? SelectedPackageState.Selected)
            ?.selection
            ?.takeIf { it.serial == serial }
            ?.packageName
        val isBusy = serial != null && selectedPackageName != null && key(serial, selectedPackageName) in busy
        return AppLifecycleViewState(
            controlPolicy = deviceState.controlPolicy(),
            selectedPackageName = selectedPackageName,
            busy = isBusy,
        )
    }

    private fun serialOf(state: SelectedDeviceState): DeviceSerial? = when (state) {
        is SelectedDeviceState.Online -> state.device.serial
        is SelectedDeviceState.Unauthorized -> state.device.serial
        is SelectedDeviceState.Offline -> state.device.serial
        is SelectedDeviceState.Ineligible -> state.device.serial
        is SelectedDeviceState.Disconnected -> state.serial
        else -> null
    }
}
