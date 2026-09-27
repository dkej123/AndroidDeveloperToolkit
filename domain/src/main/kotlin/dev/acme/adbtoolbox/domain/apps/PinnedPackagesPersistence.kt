package dev.acme.adbtoolbox.domain.apps

/**
 * Persists the package names the user pinned to the top of the Apps list, per project. Pins are by
 * package name, not per device, so a pinned app stays pinned on every device it is installed on.
 * Like [SelectedPackagePersistence], the adapter degrades missing or corrupt data to "nothing
 * pinned" instead of throwing.
 */
interface PinnedPackagesPersistence {
    suspend fun readPinnedPackages(): Set<String>
    suspend fun writePinnedPackages(packageNames: Set<String>)
}

/** An in-memory [PinnedPackagesPersistence] for tests and for compositions without persistence. */
class FakePinnedPackagesPersistence(initial: Set<String> = emptySet()) : PinnedPackagesPersistence {
    private var stored: Set<String> = initial

    private val _writes = mutableListOf<Set<String>>()
    val writes: List<Set<String>> get() = _writes

    override suspend fun readPinnedPackages(): Set<String> = stored

    override suspend fun writePinnedPackages(packageNames: Set<String>) {
        _writes += packageNames
        stored = packageNames
    }
}
