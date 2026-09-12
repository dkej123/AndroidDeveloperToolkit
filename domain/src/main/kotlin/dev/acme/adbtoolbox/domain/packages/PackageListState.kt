package dev.acme.adbtoolbox.domain.packages

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * The immutable, observable state of one [PackageRepository] discovery (task 021), MVI-consistent
 * with this project's other state components (e.g. [dev.acme.adbtoolbox.domain.device.SelectedDeviceState]).
 * [Content.packages] is a fresh, deterministically ordered snapshot on every emission — never
 * mutated in place — so a collector always observes a fully consistent list, including while
 * bounded metadata enrichment is still filling in labels/debuggable flags for some entries.
 */
sealed interface PackageListState {

    /** No discovery has completed yet for the currently requested serial. */
    data object Loading : PackageListState

    /**
     * [packages] is sorted deterministically (case-insensitive label, then package name as a
     * tie-break) and deduplicated by package name — stable regardless of how many times metadata
     * enrichment has updated individual entries since the list was first parsed.
     */
    data class Content(
        val serial: DeviceSerial,
        val scope: PackageListScope,
        val packages: List<PackageEntry>,
    ) : PackageListState

    /** The `pm list packages` call itself failed (transport failure, timeout, non-zero exit). */
    data class Error(val serial: DeviceSerial, val scope: PackageListScope, val message: String) : PackageListState
}
