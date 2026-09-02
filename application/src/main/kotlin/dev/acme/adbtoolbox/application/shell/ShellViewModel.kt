package dev.acme.adbtoolbox.application.shell

import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The feature ViewModel for the plugin's neutral tool-window content (ADR 0004's MVI seam,
 * proven end to end for task 007's placeholder rather than a real feature). A thin `:intellij`
 * view renders [state] on EDT and forwards user input to [handle] — this class never touches
 * IntelliJ/Swing types, so it is unit-testable with a test [DispatcherProvider] and no IDE fixture.
 *
 * [scope] is a feature-local child scope owned by the caller (the `:intellij` composition root),
 * never created here, so the ViewModel's lifecycle is exactly as long as the scope it is handed
 * (ADR 0004's coroutine-scope-ownership rule).
 */
class ShellViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) {
    private val _state = MutableStateFlow(ShellViewState())
    val state: StateFlow<ShellViewState> = _state.asStateFlow()

    private val _effects = Channel<ShellEffect>(Channel.BUFFERED)
    val effects: Flow<ShellEffect> = _effects.receiveAsFlow()

    fun handle(intent: ShellIntent) {
        when (intent) {
            is ShellIntent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        _state.value = _state.value.copy(isRefreshing = true)
        scope.launch {
            // Placeholder side effect: proves intents can dispatch background work via the
            // injected dispatcher (never Dispatchers.IO/Default referenced directly, ADR 0004)
            // without a real use case to call yet — device/tool discovery wiring is task 004/005's
            // concern, not this one.
            withContext(dispatchers.default) {
                _state.value = _state.value.copy(isRefreshing = false, statusMessage = "Ready")
            }
            _effects.send(ShellEffect.ShowMessage("Refreshed"))
        }
    }
}
