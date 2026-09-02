package dev.acme.adbtoolbox.domain.discovery

/** A deterministic [AndroidSdkPlatformToolsSource] test double: reports [directory] as-is, `null` by
 * default (no SDK known). */
class FakeAndroidSdkPlatformToolsSource(var directory: String? = null) : AndroidSdkPlatformToolsSource {
    override suspend fun platformToolsDirectory(): String? = directory
}
