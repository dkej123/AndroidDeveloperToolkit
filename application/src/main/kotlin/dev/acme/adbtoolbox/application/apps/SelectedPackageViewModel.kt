package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackagePersistence
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the one platform-free selected-package contract for a project (task 022), persisted via
 * [persistence] and exposed as [state] for both the Apps feature (which drives [handle]) and
 * Logcat's future default-package-filter consumption (`design/README.md`'s "Selection sharing"
 * note) — Logcat only ever reads [state], it never calls [handle] itself.
 *
 * Mirrors [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel]'s shape: [state] is a
 * single `combine` of the intended selection and whether persistence restore has resolved, so every
 * emission is recomputed from the *current* value of each input, and [collectLatest] on the write
 * side guards against an in-flight write being clobbered out of order by a newer one.
 */
class SelectedPackageViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val persistence: SelectedPackagePersistence,
) {
    private val _selection = MutableStateFlow<SelectedPackage?>(null)
    private val _restored = MutableStateFlow(false)

    private val writeRequests = MutableSharedFlow<SelectedPackage?>(extraBufferCapacity = 1)

    @Volatile
    private var explicitSelectionHandled = false

    val state: StateFlow<SelectedPackageState> =
        combine(_selection, _restored, ::reduce)
            .stateIn(scope, SharingStarted.Eagerly, SelectedPackageState.Loading)

    init {
        restore()
        scope.launch(dispatchers.io) {
            writeRequests.collectLatest { selection -> persistence.writeSelectedPackage(selection) }
        }
    }

    fun handle(intent: SelectedPackageIntent) {
        when (intent) {
            is SelectedPackageIntent.Select -> select(SelectedPackage(intent.serial, intent.packageName))
            SelectedPackageIntent.Clear -> select(null)
        }
    }

    private fun select(selection: SelectedPackage?) {
        explicitSelectionHandled = true
        _selection.value = selection
        writeRequests.tryEmit(selection)
    }

    private fun restore() {
        scope.launch(dispatchers.io) {
            runCatching { persistence.readSelectedPackage() }
                .onSuccess { persisted -> if (!explicitSelectionHandled) _selection.value = persisted }
                .onFailure { /* Degrades to "no selection" — a persisted read failure is never a crash. */ }
            _restored.value = true
        }
    }

    private fun reduce(selection: SelectedPackage?, restored: Boolean): SelectedPackageState = when {
        !restored -> SelectedPackageState.Loading
        selection == null -> SelectedPackageState.None
        else -> SelectedPackageState.Selected(selection)
    }
}
