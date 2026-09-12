package dev.acme.adbtoolbox.domain.apps

/**
 * The platform-free selected-package application contract (task 022) that Logcat (task 032/037)
 * consumes as its default package filter, per `design/README.md`'s "Selection sharing" note: "the
 * package selected in Apps is the default Logcat package filter". Contains no UI type, mirroring
 * [dev.acme.adbtoolbox.domain.device.SelectedDeviceState]'s shape.
 */
sealed interface SelectedPackageState {

    /** Persisted selection has not been read yet — no decision can be made. */
    data object Loading : SelectedPackageState

    /** No package is selected: nothing was ever chosen, or the selection was cleared/invalidated. */
    data object None : SelectedPackageState

    /** [selection] is the current selection — always paired with the exact serial it was made on. */
    data class Selected(val selection: SelectedPackage) : SelectedPackageState
}
