package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.selectedSerialOrNull
import dev.acme.adbtoolbox.domain.packages.PackageEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The package Logcat is narrowed to, chosen in Logcat's own picker — deliberately independent of
 * the Apps selection (picking an app to restart or inspect must not filter the log). Chosen by
 * package name, so it follows the selected device; [state] has the same shape as the Apps
 * selection so the pid tracker and controls consume either.
 */
class LogcatPackageSelection(
    scope: CoroutineScope,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
    initialPackageName: String? = null,
) {
    private val chosen = MutableStateFlow(initialPackageName)

    /** The chosen package name, `null` for all packages. */
    val packageName: StateFlow<String?> = chosen.asStateFlow()

    val state: StateFlow<SelectedPackageState> = combine(selectedDeviceState, chosen) { device, packageName ->
        val serial = device.selectedSerialOrNull
        if (serial == null || packageName == null) {
            SelectedPackageState.None
        } else {
            SelectedPackageState.Selected(SelectedPackage(serial, packageName))
        }
    }.stateIn(scope, SharingStarted.Eagerly, SelectedPackageState.None)

    fun select(packageName: String?) {
        chosen.value = packageName
    }
}

/** One row of Logcat's package picker. */
data class LogcatPackageChoice(val packageName: String, val label: String, val pinned: Boolean) {
    companion object {
        /** Pinned apps first (the same pins as the Apps list), each group in [entries]' order. */
        fun from(entries: List<PackageEntry>, pinned: Set<String>): List<LogcatPackageChoice> {
            val (pinnedEntries, others) = entries.partition { it.packageName in pinned }
            return (pinnedEntries + others).map { LogcatPackageChoice(it.packageName, it.label, it.packageName in pinned) }
        }
    }
}
