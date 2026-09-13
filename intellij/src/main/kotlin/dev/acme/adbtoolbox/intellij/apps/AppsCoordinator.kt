package dev.acme.adbtoolbox.intellij.apps

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.apps.AppLifecycleIntent
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewModel
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewState
import dev.acme.adbtoolbox.application.apps.AppsIntent
import dev.acme.adbtoolbox.application.apps.AppsViewModel
import dev.acme.adbtoolbox.application.apps.AppsViewState
import dev.acme.adbtoolbox.application.apps.ClearDataIntent
import dev.acme.adbtoolbox.application.apps.ClearDataViewModel
import dev.acme.adbtoolbox.application.apps.ClearDataViewState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects task 022's [AppsViewModel] and task 023's [AppLifecycleViewModel] to [AppsPanel],
 * following [dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarCoordinator]'s established
 * shape: [scope] is owned by the caller (never created here), state is collected and marshaled onto
 * [dispatchers]' `main` context before touching Swing, and [render]/[renderLifecycle] are
 * `internal`/non-suspend so they are directly unit-testable against constructed state values
 * without depending on a real coroutine round trip through this headless test sandbox.
 *
 * [appLifecycleViewModel] is reused verbatim by
 * [dev.acme.adbtoolbox.intellij.apps.RestartAppAction]'s global restart shortcut/action (both reach
 * this project's one shared instance via
 * [dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService]) — the panel button and the
 * global action can never diverge into two different restart code paths.
 */
class AppsCoordinator(
    private val viewModel: AppsViewModel,
    private val appLifecycleViewModel: AppLifecycleViewModel,
    private val clearDataViewModel: ClearDataViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : Disposable {

    val panel = AppsPanel(
        onQueryChange = { query -> viewModel.handle(AppsIntent.SetQuery(query)) },
        onToggleSystemPackages = { viewModel.handle(AppsIntent.ToggleSystemPackages) },
        onSelect = { packageName -> viewModel.handle(AppsIntent.SelectPackage(packageName)) },
        onClearFilter = { viewModel.handle(AppsIntent.ClearFilter) },
        onForceStop = { appLifecycleViewModel.handle(AppLifecycleIntent.ForceStop) },
        onLaunch = { appLifecycleViewModel.handle(AppLifecycleIntent.Launch) },
        onRestart = { appLifecycleViewModel.handle(AppLifecycleIntent.Restart) },
        onClearData = { clearDataViewModel.handle(ClearDataIntent.ClearData) },
    )

    init {
        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
        appLifecycleViewModel.state
            .onEach { state -> withContext(dispatchers.main) { renderLifecycle(state) } }
            .launchIn(scope)
        clearDataViewModel.state
            .onEach { state -> withContext(dispatchers.main) { renderClearData(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: AppsViewState) {
        panel.update(state)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun renderLifecycle(state: AppLifecycleViewState) {
        panel.updateLifecycle(state)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun renderClearData(state: ClearDataViewState) {
        panel.updateClearData(state)
    }

    override fun dispose() {
        scope.cancel()
        panel.disposePanel()
    }
}
