package dev.acme.adbtoolbox.intellij.persistence

/**
 * The Network-recents slice of [AdbToolboxProjectState] (ADR 0006: `recentProxies`, task 032). A
 * plain mutable bean (public `var`s, no-arg constructor) as IntelliJ's `XmlSerializer` requires —
 * mirrors [DeviceSelectionState]'s shape. [recentEndpoints] stores each entry as its exact
 * `host:port` render text (`ProxyEndpoint.render()`), most-recent first — a flat `List<String>` is
 * everything `XmlSerializer` needs, so no nested bean is introduced for two already-primitive parts.
 *
 * [schemaVersion] lets [resolveNetworkRecents] recognize XML written by a shape this class no
 * longer matches and degrade to an empty list instead of misinterpreting stale data — bump
 * [CURRENT_SCHEMA_VERSION] whenever this shape changes incompatibly.
 */
class NetworkState {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var recentEndpoints: MutableList<String> = mutableListOf()

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
