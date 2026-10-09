package io.github.dkej123.devicecockpit.application.apps

import io.github.dkej123.devicecockpit.domain.devicecontext.ControlPolicy

/** Immutable presentation state for task 024's Clear-data control. */
data class ClearDataViewState(
    val controlPolicy: ControlPolicy,
    val selectedPackageName: String?,
    val busy: Boolean,
) {
    val actionEnabled: Boolean
        get() = controlPolicy == ControlPolicy.Enabled && selectedPackageName != null && !busy
}
