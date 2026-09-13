package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.logcat.LogcatPidResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reactively resolves the Apps-selected package's pid for task 035's client-side package filter,
 * and exposes [refresh] so a caller (e.g. after
 * [dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase.restart] succeeds) can re-resolve the
 * pid without touching anything else — the ordinary level/search
 * [dev.acme.adbtoolbox.domain.logcat.LogcatFilterCriteria] fields, the buffer, or the session are
 * never restarted by a pid re-resolution. A monotonic generation counter discards a resolution that
 * completes after a newer selection/refresh has already superseded it (the same stale-result
 * suppression discipline as task 021's package repository).
 */
class LogcatPackagePidTracker(
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    private val selectedPackageState: StateFlow<SelectedPackageState>,
    private val resolver: LogcatPidResolver,
) {
    private val mutableState = MutableStateFlow<LogcatPidResolution>(LogcatPidResolution.NotResolved)
    val state: StateFlow<LogcatPidResolution> = mutableState.asStateFlow()

    private var generation = 0L

    init {
        scope.launch(dispatchers.default) {
            selectedPackageState.collect { selection -> resolveFor(selection) }
        }
    }

    /** Re-resolves the currently selected package's pid, e.g. after an app restart. */
    suspend fun refresh() {
        resolveFor(selectedPackageState.value)
    }

    private suspend fun resolveFor(selection: SelectedPackageState) {
        generation++
        val myGeneration = generation
        val target = (selection as? SelectedPackageState.Selected)?.selection
        if (target == null) {
            mutableState.value = LogcatPidResolution.NotResolved
            return
        }
        val resolution = resolver.resolve(target.serial, target.packageName)
        if (generation == myGeneration) mutableState.value = resolution
    }
}
