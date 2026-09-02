package dev.acme.adbtoolbox.domain.discovery

/** Which tier of the lookup order (design/IMPLEMENTATION.md §4) produced a resolved tool: a
 * user-configured path, the Android SDK's `platform-tools` directory, or a `PATH` fallback — tried
 * in exactly this order, never chosen by any other rule. */
sealed interface ToolSource {
    data object ConfiguredPath : ToolSource

    data object AndroidSdk : ToolSource

    data object PathFallback : ToolSource
}
