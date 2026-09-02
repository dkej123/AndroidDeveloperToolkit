package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.AndroidSdkPlatformToolsSource

/** Resolves the Android SDK's `platform-tools` directory from `ANDROID_HOME`/`ANDROID_SDK_ROOT`,
 * per design/IMPLEMENTATION.md §4's discovery order. Does not itself validate the directory or the
 * executable inside it — [dev.acme.adbtoolbox.domain.discovery.ToolLocator] probes the resulting
 * candidate path; this source only reports where to look. */
class EnvironmentAndroidSdkPlatformToolsSource(
    private val environmentProvider: (String) -> String? = System::getenv,
) : AndroidSdkPlatformToolsSource {
    override suspend fun platformToolsDirectory(): String? {
        val sdkHome = environmentProvider("ANDROID_HOME") ?: environmentProvider("ANDROID_SDK_ROOT")
        return sdkHome?.let { "${it.trimEnd('/', '\\')}/platform-tools" }
    }
}
