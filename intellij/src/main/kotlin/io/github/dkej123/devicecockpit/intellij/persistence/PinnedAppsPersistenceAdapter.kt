package io.github.dkej123.devicecockpit.intellij.persistence

import io.github.dkej123.devicecockpit.domain.apps.PinnedPackagesPersistence

/**
 * The pinned-apps slice of [AdbToolboxProjectState]: a plain `XmlSerializer` bean, kept separate
 * from [AppsSelectionState] because that slice is replaced wholesale on every selection write.
 */
class PinnedAppsState {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var packageNames: MutableList<String> = mutableListOf()

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/** Blank or unknown-schema data degrades to "nothing pinned", never an exception. */
internal fun resolvePinnedApps(state: PinnedAppsState): Set<String> {
    if (state.schemaVersion != PinnedAppsState.CURRENT_SCHEMA_VERSION) return emptySet()
    return state.packageNames.map(String::trim).filter(String::isNotEmpty).toSet()
}

/** The `:intellij` [PinnedPackagesPersistence] adapter, mirroring [AppsSelectionPersistenceAdapter]. */
class PinnedAppsPersistenceAdapter(
    private val projectState: AdbToolboxProjectState,
) : PinnedPackagesPersistence {

    override suspend fun readPinnedPackages(): Set<String> = readNow()

    override suspend fun writePinnedPackages(packageNames: Set<String>) = writeNow(packageNames)

    internal fun readNow(): Set<String> = resolvePinnedApps(projectState.state.pinnedApps)

    internal fun writeNow(packageNames: Set<String>) {
        projectState.state.pinnedApps = PinnedAppsState().apply { this.packageNames = packageNames.sorted().toMutableList() }
    }
}
