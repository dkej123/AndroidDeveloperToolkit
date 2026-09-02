package dev.acme.adbtoolbox.domain.discovery

/** Resolves the Android SDK's `platform-tools` directory, if this source knows of one. A
 * [ToolLocator] may be given several of these (e.g. an environment-variable-based `:adapters-jvm`
 * adapter and an IDE-SDK-based `:intellij` adapter) and tries them in order — never IntelliJ SDK,
 * filesystem, or environment types leaking into this contract itself. */
interface AndroidSdkPlatformToolsSource {
    suspend fun platformToolsDirectory(): String?
}
