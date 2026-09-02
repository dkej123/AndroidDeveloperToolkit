package dev.acme.adbtoolbox.domain.discovery

/** Reads a user-configured executable path for a tool, if one has been set. Implemented by a
 * `:adapters-jvm` in-memory adapter today; task 038 wires a `PersistentStateComponent`-backed
 * implementation of this same port without changing this contract. */
interface ConfiguredToolPathSource {
    suspend fun configuredPath(toolId: ToolId): String?
}
