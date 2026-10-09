package io.github.dkej123.devicecockpit.intellij.apps

import io.github.dkej123.devicecockpit.intellij.apps.details.AppDetailsPanel
import io.github.dkej123.devicecockpit.application.appdetails.AppDetailsViewModel
import io.github.dkej123.devicecockpit.application.appdetails.AppDetailsState
import io.github.dkej123.devicecockpit.application.appdetails.AppDetailsIntent
import com.intellij.openapi.Disposable
import io.github.dkej123.devicecockpit.application.apps.AppLifecycleIntent
import io.github.dkej123.devicecockpit.application.apps.AppLifecycleViewModel
import io.github.dkej123.devicecockpit.application.apps.AppLifecycleViewState
import io.github.dkej123.devicecockpit.application.apps.AppsIntent
import io.github.dkej123.devicecockpit.application.apps.AppsViewModel
import io.github.dkej123.devicecockpit.application.apps.AppsViewState
import io.github.dkej123.devicecockpit.application.apps.ClearDataIntent
import io.github.dkej123.devicecockpit.application.apps.ClearDataViewModel
import io.github.dkej123.devicecockpit.application.apps.ClearDataViewState
import io.github.dkej123.devicecockpit.application.apps.UninstallIntent
import io.github.dkej123.devicecockpit.application.apps.UninstallViewModel
import io.github.dkej123.devicecockpit.application.apps.UninstallViewState
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects the Apps feature view models to [AppsPanel],
 * following [io.github.dkej123.devicecockpit.intellij.devicebar.DeviceContextBarCoordinator]'s established
 * shape: [scope] is owned by the caller (never created here), state is collected and marshaled onto
 * [dispatchers]' `main` context before touching Swing, and [render]/[renderLifecycle] are
 * `internal`/non-suspend so they are directly unit-testable against constructed state values
 * without depending on a real coroutine round trip through this headless test sandbox.
 *
 * [appLifecycleViewModel] is reused verbatim by
 * [io.github.dkej123.devicecockpit.intellij.apps.RestartAppAction]'s global restart shortcut/action (both reach
 * this project's one shared instance via
 * [io.github.dkej123.devicecockpit.intellij.composition.AdbToolboxProjectService]) — the panel button and the
 * global action can never diverge into two different restart code paths.
 */
class AppsCoordinator(
    private val viewModel: AppsViewModel,
    private val appLifecycleViewModel: AppLifecycleViewModel,
    private val clearDataViewModel: ClearDataViewModel,
    private val uninstallViewModel: UninstallViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val appDetailsViewModel: AppDetailsViewModel? = null,
) : Disposable {

    val detailsPanel = AppDetailsPanel { intent -> appDetailsViewModel?.handle(intent) }

    val panel = AppsPanel(
        onQueryChange = { query -> viewModel.handle(AppsIntent.SetQuery(query)) },
        onToggleSystemPackages = { viewModel.handle(AppsIntent.ToggleSystemPackages) },
        onSelect = { packageName -> viewModel.handle(AppsIntent.SelectPackage(packageName)) },
        onClearFilter = { viewModel.handle(AppsIntent.ClearFilter) },
        onForceStop = { appLifecycleViewModel.handle(AppLifecycleIntent.ForceStop) },
        onLaunch = { appLifecycleViewModel.handle(AppLifecycleIntent.Launch) },
        onRestart = { appLifecycleViewModel.handle(AppLifecycleIntent.Restart) },
        onClearData = { clearDataViewModel.handle(ClearDataIntent.ClearData) },
        onUninstall = { uninstallViewModel.handle(UninstallIntent.Uninstall) },
        onTogglePin = { packageName -> viewModel.handle(AppsIntent.TogglePin(packageName)) },
        onOpenDetails = { packageName -> openDetails(packageName) },
        detailsPanel = detailsPanel,
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
        uninstallViewModel.state
            .onEach { state -> withContext(dispatchers.main) { renderUninstall(state) } }
            .launchIn(scope)
        appDetailsViewModel?.state
            ?.onEach { state -> withContext(dispatchers.main) { renderDetails(state) } }
            ?.launchIn(scope)
    }

    private fun openDetails(packageName: String) {
        val row = viewModel.state.value.rows.firstOrNull { it.packageName == packageName }
        appDetailsViewModel?.handle(AppDetailsIntent.Open(packageName, row?.label ?: packageName, row?.icon))
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun renderDetails(state: AppDetailsState) {
        detailsPanel.update(state)
        panel.showDetails(state.isOpen)
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

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun renderUninstall(state: UninstallViewState) {
        panel.updateUninstall(state)
    }

    override fun dispose() {
        scope.cancel()
        panel.disposePanel()
    }
}
