package dev.acme.adbtoolbox.domain.discovery

/** Lists the directories on the host `PATH`, already split by the OS-specific separator. The
 * approved last-resort tier of the lookup order (design/IMPLEMENTATION.md §4). */
interface PathEnvironmentSource {
    suspend fun directories(): List<String>
}
