package dev.acme.adbtoolbox.application.apps

import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import dev.acme.adbtoolbox.domain.apps.FakePinnedPackagesPersistence
import dev.acme.adbtoolbox.domain.apps.PinnedPackagesPersistence
import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.packages.PackageEntry
import dev.acme.adbtoolbox.domain.packages.PackageListScope
import dev.acme.adbtoolbox.domain.packages.PackageListState
import dev.acme.adbtoolbox.domain.packages.PackageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val PINNED_HEADER = "Pinned"
private const val ALL_APPS_HEADER = "All apps"

/**
 * Task 022's Apps presenter: reduces [packageRepository]'s [PackageListState] (task 021),
 * [selectedDeviceViewModel]'s device context, [selectedPackageViewModel]'s shared selection
 * contract, and local query/scope-toggle intents into [AppsViewState].
 *
 * **Refresh-on-context-change**: whenever the active device serial or the user/system scope toggle
 * changes, this class fires [PackageRepository.refresh] itself (fire-and-forget; [packageRepository]
 * already suppresses stale results per task 021) — the rendering layer never has to remember to
 * request a refresh.
 *
 * **Revalidation**: whenever a fresh [PackageListState.Content] lands for the serial the current
 * selection was made on, and that selection's package name is no longer present in it (app
 * uninstalled, or a stale/corrupt persisted selection), the selection is cleared through
 * [selectedPackageViewModel] — never left dangling or silently misapplied.
 *
 * **Cross-device safety**: [reduce] only ever surfaces [SelectedPackageState.Selected] when its
 * [dev.acme.adbtoolbox.domain.apps.SelectedPackage.serial] equals the *currently active* device
 * serial — switching devices never shows (or lets footer actions target) another device's
 * leftover selection.
 */
class AppsViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val packageRepository: PackageRepository,
    private val selectedDeviceViewModel: SelectedDeviceViewModel,
    private val selectedPackageViewModel: SelectedPackageViewModel,
    private val pinnedPackages: PinnedPackagesPersistence = FakePinnedPackagesPersistence(),
) {
    private val _query = MutableStateFlow("")
    private val _pinned = MutableStateFlow<Set<String>>(emptySet())

    /** Pinned package names (by name, on every device), shared with other package pickers such as Logcat's. */
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()
    private val pinWrites = MutableSharedFlow<Set<String>>(extraBufferCapacity = 1)
    private val _currentPackageScope = MutableStateFlow(PackageListScope.User)

    /** The scope selected by the Apps toggle, updated synchronously when its intent is handled. */
    val currentPackageScope: StateFlow<PackageListScope> = _currentPackageScope.asStateFlow()

    val state: StateFlow<AppsViewState> = combine(
        selectedDeviceViewModel.state,
        packageRepository.state,
        selectedPackageViewModel.state,
        combine(_query, _pinned, ::Pair),
        _currentPackageScope,
    ) { deviceState, packageState, selectionState, (query, pinned), packageScope ->
        reduce(deviceState, packageState, selectionState, query, pinned, packageScope)
    }.stateIn(scope, SharingStarted.Eagerly, AppsViewState())

    init {
        scope.launch(dispatchers.io) {
            runCatching { pinnedPackages.readPinnedPackages() }.onSuccess { loaded -> _pinned.update { it + loaded } }
        }
        scope.launch(dispatchers.io) { pinWrites.collectLatest { pinnedPackages.writePinnedPackages(it) } }
        scope.launch(dispatchers.default) {
            combine(selectedDeviceViewModel.state, _currentPackageScope) { deviceState, packageScope ->
                serialOf(deviceState) to packageScope
            }
                .distinctUntilChanged()
                .collect { (serial, packageScope) ->
                    if (serial != null) packageRepository.refresh(serial, packageScope)
                }
        }
        scope.launch(dispatchers.default) {
            combine(
                selectedDeviceViewModel.state,
                packageRepository.state,
                selectedPackageViewModel.state,
            ) { deviceState, packageState, selectionState -> Triple(serialOf(deviceState), packageState, selectionState) }
                .collect { (serial, packageState, selectionState) ->
                    revalidate(serial, packageState, selectionState)
                }
        }
    }

    fun handle(intent: AppsIntent) {
        when (intent) {
            is AppsIntent.SetQuery -> _query.value = intent.query
            AppsIntent.ClearFilter -> _query.value = ""
            AppsIntent.ToggleSystemPackages -> _currentPackageScope.value = when (_currentPackageScope.value) {
                PackageListScope.User -> PackageListScope.All
                PackageListScope.All -> PackageListScope.User
            }
            is AppsIntent.SelectPackage -> selectPackage(intent.packageName)
            is AppsIntent.TogglePin -> {
                val updated = _pinned.updateAndGet { pins ->
                    if (intent.packageName in pins) pins - intent.packageName else pins + intent.packageName
                }
                pinWrites.tryEmit(updated)
            }
            AppsIntent.Refresh -> refresh()
        }
    }

    private fun selectPackage(packageName: String?) {
        val serial = serialOf(selectedDeviceViewModel.state.value) ?: return
        if (packageName == null) {
            selectedPackageViewModel.handle(SelectedPackageIntent.Clear)
        } else {
            selectedPackageViewModel.handle(SelectedPackageIntent.Select(serial, packageName))
        }
    }

    private fun refresh() {
        val serial = serialOf(selectedDeviceViewModel.state.value) ?: return
        packageRepository.refresh(serial, _currentPackageScope.value)
    }

    private fun revalidate(serial: DeviceSerial?, packageState: PackageListState, selectionState: SelectedPackageState) {
        val selection = (selectionState as? SelectedPackageState.Selected)?.selection ?: return
        if (serial == null || selection.serial != serial) return
        if (packageState !is PackageListState.Content || packageState.serial != serial) return
        val stillPresent = packageState.packages.any { it.packageName == selection.packageName }
        if (!stillPresent) selectedPackageViewModel.handle(SelectedPackageIntent.Clear)
    }

    private fun reduce(
        deviceState: SelectedDeviceState,
        packageState: PackageListState,
        selectionState: SelectedPackageState,
        query: String,
        pinned: Set<String>,
        packageScope: PackageListScope,
    ): AppsViewState {
        val showSystemPackages = packageScope == PackageListScope.All
        val serial = serialOf(deviceState)
        val selectedPackageName = (selectionState as? SelectedPackageState.Selected)
            ?.selection
            ?.takeIf { it.serial == serial }
            ?.packageName

        if (serial == null) {
            return AppsViewState(
                query = query,
                showSystemPackages = showSystemPackages,
                hasDevice = false,
                isLoading = false,
                rows = emptyList(),
                selectedPackageName = null,
            )
        }

        return when {
            packageState is PackageListState.Error && packageState.serial == serial ->
                AppsViewState(
                    query = query,
                    showSystemPackages = showSystemPackages,
                    hasDevice = true,
                    isLoading = false,
                    errorMessage = packageState.message,
                    rows = emptyList(),
                    selectedPackageName = selectedPackageName,
                )

            packageState is PackageListState.Content && packageState.serial == serial ->
                AppsViewState(
                    query = query,
                    showSystemPackages = showSystemPackages,
                    hasDevice = true,
                    isLoading = false,
                    rows = rowsOf(packageState.packages.filter { matches(it, query) }, pinned, selectedPackageName),
                    selectedPackageName = selectedPackageName,
                )

            // Loading, or a Content/Error still keyed to a different (stale/superseded) serial.
            else -> AppsViewState(
                query = query,
                showSystemPackages = showSystemPackages,
                hasDevice = true,
                isLoading = true,
                rows = emptyList(),
                selectedPackageName = selectedPackageName,
            )
        }
    }

    /**
     * Pinned apps first, each group in the repository's (label) order. Section headers are only
     * added when a pinned app is visible, so an unpinned list looks exactly as before.
     */
    private fun rowsOf(entries: List<PackageEntry>, pinned: Set<String>, selectedPackageName: String?): List<AppsRow> {
        val (pinnedEntries, others) = entries.partition { it.packageName in pinned }
        fun row(entry: PackageEntry, header: String?) = AppsRow(
            packageName = entry.packageName,
            label = entry.label,
            labelResolved = entry.labelResolved,
            isDebuggable = entry.isDebuggable,
            isSelected = entry.packageName == selectedPackageName,
            icon = entry.icon,
            isPinned = entry.packageName in pinned,
            sectionHeader = header,
        )
        if (pinnedEntries.isEmpty()) return others.map { row(it, null) }
        return pinnedEntries.mapIndexed { i, entry -> row(entry, if (i == 0) PINNED_HEADER else null) } +
            others.mapIndexed { i, entry -> row(entry, if (i == 0) ALL_APPS_HEADER else null) }
    }

    private fun matches(entry: PackageEntry, query: String): Boolean {
        if (query.isBlank()) return true
        return entry.label.contains(query, ignoreCase = true) || entry.packageName.contains(query, ignoreCase = true)
    }

    private fun serialOf(state: SelectedDeviceState): DeviceSerial? = when (state) {
        is SelectedDeviceState.Online -> state.device.serial
        is SelectedDeviceState.Unauthorized -> state.device.serial
        is SelectedDeviceState.Offline -> state.device.serial
        is SelectedDeviceState.Ineligible -> state.device.serial
        is SelectedDeviceState.Disconnected -> state.serial
        else -> null
    }
}
