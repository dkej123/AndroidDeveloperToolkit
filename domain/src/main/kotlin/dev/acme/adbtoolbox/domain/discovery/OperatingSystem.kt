package dev.acme.adbtoolbox.domain.discovery

/** The host OS family, as reported by an adapter-owned [HostPlatformProvider]. Determines executable
 * naming and path separators for tool discovery — never detected directly in `:domain`. */
enum class OperatingSystem { MacOs, Linux, Windows }
