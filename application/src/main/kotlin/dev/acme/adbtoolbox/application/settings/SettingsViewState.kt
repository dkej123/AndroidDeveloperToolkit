package dev.acme.adbtoolbox.application.settings

import dev.acme.adbtoolbox.domain.settings.SettingsFieldError
import dev.acme.adbtoolbox.domain.settings.SettingsState

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
