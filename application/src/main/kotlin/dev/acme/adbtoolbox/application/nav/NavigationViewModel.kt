package dev.acme.adbtoolbox.application.nav

import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.NavigationPersistence
import dev.acme.adbtoolbox.domain.nav.NavigationState
import dev.acme.adbtoolbox.domain.nav.ViewId
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
 * Owns the one selected-view context for a project (task 012), the same MVI shape as
 * [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel] (ADR 0004):
 * [scope]/[dispatchers] follow the feature-local-child-scope/dispatcher-injection seam, and
 * [state] combines the currently intended [ViewId] with whether persistence restore has resolved,
 * so every emission is recomputed from the *current* value of each input. [defaultViewId] (task
 * 012's scope: "a sensible default ... when missing or corrupt") is a caller-supplied [ViewId] —
 * production wiring passes [ViewId.Device] — used whenever [persistence] has no usable persisted
 * value, on first launch or after degrading corrupt/older-schema data. This ViewModel has no
 * notion of whether a [ViewId] is actually registered with a feature view yet — that is a
 * `:intellij`-layer routing concern (`NavigationRoutingCoordinator`), kept out of this KMP-ready
 * layer per ADR 0002.
 */
class NavigationViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val persistence: NavigationPersistence,
    private val defaultViewId: ViewId = ViewId.Device,
) {
    private val _selected = MutableStateFlow(defaultViewId)
    private val _restored = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    private val writeRequests = MutableSharedFlow<ViewId>(extraBufferCapacity = 1)

    val state: StateFlow<NavigationState> =
        combine(_selected, _restored, _error, ::reduce)
            .stateIn(scope, SharingStarted.Eagerly, NavigationState.Loading)

    init {
        restore()
        scope.launch(dispatchers.io) {
            writeRequests.collectLatest { viewId -> persistence.writeLastView(viewId) }
        }
    }

    fun handle(intent: NavigationIntent) {
        when (intent) {
            is NavigationIntent.Select -> select(intent.viewId)
            NavigationIntent.RetryRestore -> restore()
        }
    }

    private fun select(viewId: ViewId) {
        _selected.value = viewId
        _error.value = null
        writeRequests.tryEmit(viewId)
    }

    private fun restore() {
        scope.launch(dispatchers.io) {
            runCatching { persistence.readLastView() }
                .onSuccess { persisted ->
                    _error.value = null
                    _selected.value = persisted ?: defaultViewId
                    _restored.value = true
                }
                .onFailure { failure ->
                    _error.value = failure.message ?: failure::class.simpleName ?: "Unknown error"
                }
        }
    }

    private fun reduce(selected: ViewId, restored: Boolean, error: String?): NavigationState = when {
        error != null -> NavigationState.Error(error)
        !restored -> NavigationState.Loading
        else -> NavigationState.Ready(selected)
    }
}
