package io.github.dkej123.devicecockpit.application.settings

import io.github.dkej123.devicecockpit.domain.settings.SettingsFieldError
import io.github.dkej123.devicecockpit.domain.settings.SettingsState

data class SettingsViewState(
    val persisted: SettingsState = SettingsState.DEFAULT,
    val draft: SettingsState = SettingsState.DEFAULT,
    val validationErrors: Set<SettingsFieldError> = emptySet(),
    val isLoading: Boolean = true,
    val isApplying: Boolean = false,
    val errorMessage: String? = null,
) {
    val isModified: Boolean get() = draft != persisted
}
