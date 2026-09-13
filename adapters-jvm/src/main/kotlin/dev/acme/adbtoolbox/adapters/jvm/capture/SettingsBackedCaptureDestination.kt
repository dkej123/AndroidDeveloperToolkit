package dev.acme.adbtoolbox.adapters.jvm.capture

import dev.acme.adbtoolbox.domain.capture.CaptureDestination
import dev.acme.adbtoolbox.domain.capture.CaptureTarget
import dev.acme.adbtoolbox.domain.settings.SettingsRepository
import java.nio.file.Path

/**
 * Resolves task 038's project-scoped capture directory immediately before each new capture.
 * Existing captures keep the destination chosen when they began, while subsequent captures see
 * settings changes without rebuilding the composition root. Actual file creation and commit are
 * delegated to [JvmCaptureDestination], preserving its temp-file, atomic-move, discard, and
 * collision semantics.
 */
class SettingsBackedCaptureDestination(
    private val settingsRepository: SettingsRepository,
    private val defaultDirectory: Path = Path.of(System.getProperty("user.home"), "Desktop"),
) : CaptureDestination {
    override suspend fun beginCapture(baseFileName: String): CaptureTarget {
        val configuredDirectory = settingsRepository.readSettings()
            .captureDirectory
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(Path::of)
        return JvmCaptureDestination(configuredDirectory ?: defaultDirectory)
            .beginCapture(baseFileName)
    }
}
