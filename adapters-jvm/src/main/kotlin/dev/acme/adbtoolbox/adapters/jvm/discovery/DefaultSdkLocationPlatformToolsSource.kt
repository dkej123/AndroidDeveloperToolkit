package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.AndroidSdkPlatformToolsSource
import dev.acme.adbtoolbox.domain.discovery.OperatingSystem

/**
 * The Android SDK location Android Studio's setup wizard installs to by default, per OS. An IDE
 * started from the macOS Dock/Finder (or a Linux desktop launcher) does not inherit the shell's
 * `ANDROID_HOME`/`PATH`, so this is the tier that still finds a stock SDK install there. Like the
 * other SDK sources it only reports where to look; the tool locator probes the executable.
 */
class DefaultSdkLocationPlatformToolsSource(
    private val hostPlatform: () -> OperatingSystem,
    private val userHome: () -> String? = { System.getProperty("user.home") },
    private val environmentProvider: (String) -> String? = System::getenv,
) : AndroidSdkPlatformToolsSource {
    override suspend fun platformToolsDirectory(): String? {
        val sdk = when (hostPlatform()) {
            OperatingSystem.MacOs -> userHome()?.let { "${it.trimEnd('/')}/Library/Android/sdk" }
            OperatingSystem.Linux -> userHome()?.let { "${it.trimEnd('/')}/Android/Sdk" }
            OperatingSystem.Windows -> environmentProvider("LOCALAPPDATA")?.let { "${it.trimEnd('/', '\\')}/Android/Sdk" }
        }
        return sdk?.let { "$it/platform-tools" }
    }
}
