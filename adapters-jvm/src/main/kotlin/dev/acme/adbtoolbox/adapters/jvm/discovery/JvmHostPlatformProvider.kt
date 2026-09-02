package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.HostPlatformProvider
import dev.acme.adbtoolbox.domain.discovery.OperatingSystem

/** Maps a raw `os.name` system-property value to [OperatingSystem]. Pure so it can be unit-tested
 * against every OS family without depending on the actual host running the test. */
fun mapOsName(rawOsName: String): OperatingSystem {
    val normalized = rawOsName.lowercase()
    return when {
        normalized.contains("mac") || normalized.contains("darwin") -> OperatingSystem.MacOs
        normalized.contains("win") -> OperatingSystem.Windows
        else -> OperatingSystem.Linux
    }
}

/** Reads the real `os.name` JVM system property. The only place tool discovery detects the host
 * OS — isolated here per ADR 0002 (`:domain` must stay free of JVM system-property access). */
class JvmHostPlatformProvider(
    private val osNameProvider: () -> String = { System.getProperty("os.name") ?: "" },
) : HostPlatformProvider {
    override fun current(): OperatingSystem = mapOsName(osNameProvider())
}
