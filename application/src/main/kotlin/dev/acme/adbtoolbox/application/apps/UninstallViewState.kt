package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy

data class UninstallViewState(
    val controlPolicy: ControlPolicy,
    val selectedPackageName: String?,
    val busy: Boolean,
) {
    val actionEnabled: Boolean
        get() = controlPolicy == ControlPolicy.Enabled && selectedPackageName != null && !busy
}
