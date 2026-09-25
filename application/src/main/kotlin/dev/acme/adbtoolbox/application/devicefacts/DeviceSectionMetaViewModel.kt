package dev.acme.adbtoolbox.application.devicefacts

import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolLocator
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The right-aligned meta text of the Device view's Mirroring and Capture section headers. */
data class DeviceSectionMeta(val mirroring: String, val capture: String)

/**
 * Derives [DeviceSectionMeta] from what is actually installed and configured — the resolved scrcpy
 * version and the effective capture directory — instead of fixed text. [refresh] re-reads both;
 * the composition root calls it when the scrcpy path or capture directory setting changes.
 */
class DeviceSectionMetaViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val toolLocator: ToolLocator,
    private val settings: SettingsRepository,
    private val homeDirectory: String,
    private val defaultCaptureDirectory: String,
) {
    private val _state = MutableStateFlow(DeviceSectionMeta(mirroring = "scrcpy", capture = abbreviate(defaultCaptureDirectory)))
    val state: StateFlow<DeviceSectionMeta> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch(dispatchers.io) {
            val mirroring = when (val outcome = toolLocator.locate(ToolId.Scrcpy)) {
                is DiscoveryOutcome.Found -> "scrcpy ${outcome.tool.version}"
                is DiscoveryOutcome.Failed -> "scrcpy not found"
            }
            val configured = runCatching { settings.readSettings().captureDirectory }.getOrNull()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            _state.value = DeviceSectionMeta(mirroring, abbreviate(configured ?: defaultCaptureDirectory))
        }
    }

    private fun abbreviate(path: String): String {
        val trimmed = path.trimEnd('/', '\\').ifEmpty { path }
        val home = homeDirectory.trimEnd('/', '\\')
        return when {
            home.isEmpty() -> trimmed
            trimmed == home -> "~"
            trimmed.startsWith("$home/") || trimmed.startsWith("$home\\") -> "~" + trimmed.substring(home.length)
            else -> trimmed
        }
    }
}
