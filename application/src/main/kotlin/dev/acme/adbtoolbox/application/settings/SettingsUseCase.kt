package dev.acme.adbtoolbox.application.settings

import dev.acme.adbtoolbox.domain.discovery.ExecutableFileProbe
import dev.acme.adbtoolbox.domain.settings.DirectoryProbe
import dev.acme.adbtoolbox.domain.settings.SettingsFieldError
import dev.acme.adbtoolbox.domain.settings.SettingsDependency
import dev.acme.adbtoolbox.domain.settings.SettingsInvalidationPort
import dev.acme.adbtoolbox.domain.settings.SettingsRepository
import dev.acme.adbtoolbox.domain.settings.SettingsState
import dev.acme.adbtoolbox.domain.settings.SettingsValidationResult
import dev.acme.adbtoolbox.domain.settings.validateSettings

sealed interface SettingsApplyResult {
    data class Applied(val state: SettingsState) : SettingsApplyResult
    data class Invalid(val errors: Set<SettingsFieldError>) : SettingsApplyResult
}

/** Validates and persists one project-settings snapshot, then invalidates only derived state made stale by its changes. */
class SettingsUseCase(
    private val repository: SettingsRepository,
    private val executableProbe: ExecutableFileProbe,
    private val directoryProbe: DirectoryProbe,
    private val invalidation: SettingsInvalidationPort,
) {
    suspend fun load(): SettingsState = repository.readSettings()

    suspend fun apply(candidate: SettingsState): SettingsApplyResult =
        when (val validation = validateSettings(candidate, executableProbe, directoryProbe)) {
            is SettingsValidationResult.Invalid -> SettingsApplyResult.Invalid(validation.errors)
            is SettingsValidationResult.Valid -> applyValidated(validation.state)
        }

    private suspend fun applyValidated(validated: SettingsState): SettingsApplyResult {
        val previous = repository.readSettings()
        repository.writeSettings(validated)
        val changed = changedDependencies(previous, validated)
        if (changed.isNotEmpty()) invalidation.invalidate(changed)
        return SettingsApplyResult.Applied(validated)
    }

    private fun changedDependencies(previous: SettingsState, current: SettingsState): Set<SettingsDependency> = buildSet {
        if (previous.adbPathOverride != current.adbPathOverride) add(SettingsDependency.AdbPath)
        if (previous.scrcpyPathOverride != current.scrcpyPathOverride) add(SettingsDependency.ScrcpyPath)
        if (previous.captureDirectory != current.captureDirectory) add(SettingsDependency.CaptureDirectory)
        if (previous.logcatBufferSizeKb != current.logcatBufferSizeKb) add(SettingsDependency.LogcatBufferSize)
    }
}
