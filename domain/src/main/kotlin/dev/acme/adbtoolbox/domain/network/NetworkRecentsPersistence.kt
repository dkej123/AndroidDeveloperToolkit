package dev.acme.adbtoolbox.domain.network

/**
 * Persists the project-scoped MRU proxy-endpoint list (ADR 0006: `recentProxies`) across IDE
 * restarts — not per-serial, since recents are shared across whichever device is currently
 * selected. The concrete adapter (`:intellij`) must never let missing, corrupt, or older-schema
 * persisted data throw — it degrades to an empty list instead, mirroring
 * [dev.acme.adbtoolbox.domain.device.DeviceSelectionPersistence]'s contract. A thrown exception
 * signals a genuine, unexpected failure, which callers treat as recoverable, never a crash.
 */
interface NetworkRecentsPersistence {
    suspend fun readRecents(): List<ProxyEndpoint>
    suspend fun writeRecents(recents: List<ProxyEndpoint>)
}
