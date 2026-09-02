package dev.acme.adbtoolbox.domain.discovery

/**
 * The single tool-discovery port (ADR 0001): implemented by `:adapters-adb`'s `DefaultToolLocator`,
 * built on `:adapters-jvm`'s process executor and filesystem/environment adapters plus (optionally)
 * `:intellij`'s IDE-SDK-based [AndroidSdkPlatformToolsSource]. Resolves [toolId] via the lookup
 * order fixed by design/IMPLEMENTATION.md §4: configured path, then Android SDK platform-tools,
 * then an approved `PATH` fallback — validating the executable and confirming its version before
 * reporting success.
 */
interface ToolLocator {
    /** Resolves [toolId], returning a cached result unless [forceRefresh] is `true`. Cancelling the
     * calling coroutine tears down any in-flight version-query process, per ADR 0005. */
    suspend fun locate(toolId: ToolId, forceRefresh: Boolean = false): DiscoveryOutcome

    /** Drops any cached result for [toolId] so the next [locate] call re-resolves it from scratch. */
    suspend fun invalidate(toolId: ToolId)
}
