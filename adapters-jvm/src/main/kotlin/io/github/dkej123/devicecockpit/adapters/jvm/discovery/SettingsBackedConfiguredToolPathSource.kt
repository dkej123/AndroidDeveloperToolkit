package io.github.dkej123.devicecockpit.adapters.jvm.discovery

import io.github.dkej123.devicecockpit.domain.discovery.ConfiguredToolPathSource
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.settings.SettingsRepository

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
