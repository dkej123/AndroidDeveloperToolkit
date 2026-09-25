package dev.acme.adbtoolbox.application.diagnostics

import dev.acme.adbtoolbox.application.feedback.FeedbackViewState
import dev.acme.adbtoolbox.domain.device.DeviceRepository
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.DiagnosticsLog
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Passive collectors that write state transitions the user can see (device list, discovery
 * errors, selected device, toasts) into the diagnostics log. They only read existing state flows;
 * all of them stop with [scope].
 */
object DiagnosticsObservers {

    fun start(
        scope: CoroutineScope,
        log: DiagnosticsLog,
        deviceRepository: DeviceRepository,
        selectedDevice: StateFlow<SelectedDeviceState>,
        feedback: StateFlow<FeedbackViewState>,
    ) {
        scope.launch {
            deviceRepository.devices.collect { devices ->
                    log.log(
                        DiagLevel.INFO,
                        DiagCategory.DEVICE,
                        "device list changed",
                        mapOf(
                            "count" to devices.size,
                            "devices" to devices.joinToString(", ") { "${it.serial}:${it.state}:${it.model ?: "?"}" },
                        ),
                    )
                }
        }
        scope.launch {
            deviceRepository.listError
                .drop(1)
                .collect { error ->
                    if (error != null) {
                        log.log(DiagLevel.WARN, DiagCategory.DEVICE, "device list query failed", mapOf("error" to error))
                    } else {
                        log.log(DiagLevel.INFO, DiagCategory.DEVICE, "device list query recovered")
                    }
                }
        }
        scope.launch {
            selectedDevice.collect { state -> log.log(DiagLevel.INFO, DiagCategory.DEVICE, "selection changed", mapOf("state" to state)) }
        }
        scope.launch {
            val seen = mutableSetOf<String>()
            feedback.collect { state ->
                state.toasts.filter { seen.add(it.id) }.forEach { toast ->
                    val level = when (toast.severity) {
                        FeedbackSeverity.Error, FeedbackSeverity.Warning -> DiagLevel.WARN
                        else -> DiagLevel.INFO
                    }
                    log.log(level, DiagCategory.FEEDBACK, "toast", mapOf("severity" to toast.severity, "text" to toast.text))
                }
            }
        }
    }
}
