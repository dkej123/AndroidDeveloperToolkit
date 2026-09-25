package dev.acme.adbtoolbox.application.nav

import dev.acme.adbtoolbox.domain.nav.NavigationState
import dev.acme.adbtoolbox.domain.nav.ViewId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Re-reads a view's device state each time the user enters that view. Views such as Apps and the
 * Display quick toggles otherwise only query the device when the selected device changes, so
 * anything changed outside the plugin (an app installed by Run, a setting toggled on the phone)
 * stayed stale until the next device switch.
 */
class ViewEnterRefresher(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    navigation: Flow<NavigationState>,
    private val refreshers: Map<ViewId, () -> Unit>,
) {
    @Volatile
    private var current: ViewId? = null

    init {
        scope.launch(dispatcher) {
            navigation
                .map { (it as? NavigationState.Ready)?.selected }
                .distinctUntilChanged()
                .collect { view ->
                    current = view
                    view?.let(refreshers::get)?.invoke()
                }
        }
    }

    /** Re-reads the view on screen, e.g. when the user returns to the tool window. */
    fun refreshCurrent() {
        current?.let(refreshers::get)?.invoke()
    }
}
