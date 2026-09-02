package dev.acme.adbtoolbox.domain.discovery

/** Checks whether [path] points to a real, executable file on the host filesystem. The only place
 * tool discovery touches the filesystem — always behind this port, never inline in `:domain` or
 * `:adapters-adb`. */
interface ExecutableFileProbe {
    suspend fun isExecutable(path: String): Boolean
}
