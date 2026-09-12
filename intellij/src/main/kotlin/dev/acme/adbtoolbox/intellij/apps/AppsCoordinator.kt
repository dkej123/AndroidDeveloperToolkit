package dev.acme.adbtoolbox.intellij.apps

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.apps.AppsIntent
import dev.acme.adbtoolbox.application.apps.AppsViewModel
import dev.acme.adbtoolbox.application.apps.AppsViewState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects task 022's [AppsViewModel] to [AppsPanel], following
 * [dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarCoordinator]'s established shape:
 * [scope] is owned by the caller (never created here), state is collected and marshaled onto
 * [dispatchers]' `main` context before touching Swing, and [render] is `internal`/non-suspend so it
 * is directly unit-testable against constructed [AppsViewState] values without depending on a real
 * coroutine round trip through this headless test sandbox.
 */
class AppsCoordinator(
    private val viewModel: AppsViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : Disposable {

    val panel = AppsPanel(
        onQueryChange = { query -> viewModel.handle(AppsIntent.SetQuery(query)) },
        onToggleSystemPackages = { viewModel.handle(AppsIntent.ToggleSystemPackages) },
        onSelect = { packageName -> viewModel.handle(AppsIntent.SelectPackage(packageName)) },
        onClearFilter = { viewModel.handle(AppsIntent.ClearFilter) },
    )

    init {
        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: AppsViewState) {
        panel.update(state)
    }

    override fun dispose() {
        scope.cancel()
        panel.disposePanel()
    }
}
