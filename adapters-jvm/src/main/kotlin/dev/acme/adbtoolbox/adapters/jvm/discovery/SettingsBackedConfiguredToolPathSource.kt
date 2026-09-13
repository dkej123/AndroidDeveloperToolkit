package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.ConfiguredToolPathSource
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.settings.SettingsRepository

/**
 * The persisted [ConfiguredToolPathSource] (task 038): reads the approved adb/scrcpy path override
 * from [settingsRepository] (ADR 0006, project-scoped, `PersistentStateComponent`-backed in
 * `:intellij`) rather than [InMemoryConfiguredToolPathSource]'s process-lifetime-only map, so a
 * configured path survives IDE restarts. Depends only on the `:domain` [SettingsRepository] port —
 * no IntelliJ type reaches this module.
 */
class SettingsBackedConfiguredToolPathSource(
    private val settingsRepository: SettingsRepository,
) : ConfiguredToolPathSource {

    override suspend fun configuredPath(toolId: ToolId): String? {
        val settings = settingsRepository.readSettings()
        return when (toolId) {
            ToolId.Adb -> settings.adbPathOverride
            ToolId.Scrcpy -> settings.scrcpyPathOverride
        }?.trim()?.takeIf(String::isNotEmpty)
    }
}
