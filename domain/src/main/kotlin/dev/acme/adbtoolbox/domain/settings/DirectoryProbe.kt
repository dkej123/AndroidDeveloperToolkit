package dev.acme.adbtoolbox.domain.settings

/** Checks whether [path] points to an existing directory on the host filesystem — the only place
 * settings validation touches the filesystem for the capture-directory field, never inline in
 * `:domain` or `:intellij`. Mirrors [dev.acme.adbtoolbox.domain.discovery.ExecutableFileProbe]'s
 * shape. */
interface DirectoryProbe {
    suspend fun isValidDirectory(path: String): Boolean
}
