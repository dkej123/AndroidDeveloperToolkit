package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator
import dev.acme.adbtoolbox.application.logcat.LogcatControlsController
import dev.acme.adbtoolbox.application.logcat.LogcatControlsIntent
import dev.acme.adbtoolbox.application.logcat.LogcatControlsState
import dev.acme.adbtoolbox.application.logcat.LogcatFilterUpdate
import dev.acme.adbtoolbox.domain.devicecontext.BadgeContributor
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.logcat.LogcatBufferCursor
import dev.acme.adbtoolbox.domain.logcat.LogcatBufferDelta
import dev.acme.adbtoolbox.domain.logcat.LogcatBufferSnapshot
import dev.acme.adbtoolbox.domain.logcat.LogcatPauseState
import dev.acme.adbtoolbox.domain.logcat.SequencedLogcatEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects task 037's [LogcatControlsController] to [LogcatPanel]/task 036's virtualized renderer,
 * following [dev.acme.adbtoolbox.intellij.network.NetworkCoordinator]'s established shape: [scope]
 * is owned by the caller, state is collected and marshaled onto [dispatchers]' `main` context before
 * touching Swing, and [render]/[applyFilterUpdate] are `internal`/non-suspend so they are directly
 * unit-testable against constructed values without a real coroutine round trip.
 *
 * [controller.filterUpdates][LogcatControlsController.filterUpdates] is suppressed while paused
 * (`design/README.md`'s "frozen view point": ingestion/filtering keep running underneath, but the
 * rendered rows must not move) and replayed in full from [LogcatControlsController.snapshot] the
 * moment [LogcatControlsState.pauseState] transitions back to [LogcatPauseState.Resumed] — this is
 * purely a rendering decision, so it lives here rather than in the controller's own reducer.
 * [applyFilterUpdate] computes search-match spans via [LogcatMatchSpanCalculator] against each
 * entry's exact rendered text ([LogcatRenderBatchFactory.messageTextFor]) before handing batches to
 * [edtBatcher] — the renderer itself never re-parses or searches raw logcat text.
 */
class LogcatCoordinator(
    private val controller: LogcatControlsController,
    private val aggregator: DeviceContextAggregator,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    val virtualList: LogcatVirtualList = LogcatVirtualList(),
    private val edtBatcher: LogcatEdtBatcher = LogcatEdtBatcher(virtualList.virtualModel),
    badgeContributor: BadgeContributor = LogcatBadgeContributor(controller),
) : Disposable {

    val panel = LogcatPanel(
        virtualList = virtualList,
        onQueryChange = { text -> controller.handle(LogcatControlsIntent.SetQuery(text)) },
        onSetMinSeverity = { level -> controller.handle(LogcatControlsIntent.SetMinSeverity(level)) },
        onTogglePackageFilter = { controller.handle(LogcatControlsIntent.TogglePackageFilter) },
        onTogglePause = { controller.handle(LogcatControlsIntent.TogglePause) },
        onToggleFollow = { controller.handle(LogcatControlsIntent.ToggleFollow) },
        onToggleWrap = { controller.handle(LogcatControlsIntent.ToggleWrap) },
        onClearLocal = { controller.handle(LogcatControlsIntent.ClearLocal) },
        onJumpToLatest = { controller.handle(LogcatControlsIntent.JumpToLatest) },
        onResetFilters = { controller.handle(LogcatControlsIntent.ResetFilters) },
        onManualScrollAway = { controller.handle(LogcatControlsIntent.ManualScrollAway) },
    )

    private val badgeRegistration = aggregator.registerBadgeContributor(badgeContributor)
    private var wasPaused = false

    init {
        controller.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
        controller.filterUpdates
            .onEach { update -> withContext(dispatchers.main) { onFilterUpdate(update) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: LogcatControlsState) {
        panel.update(state)
        virtualList.presentation = virtualList.presentation.copy(wrapLines = state.wrap)
        val nowPaused = state.pauseState is LogcatPauseState.Paused
        if (wasPaused && !nowPaused) {
            applyFilterUpdate(LogcatFilterUpdate.Reset(controller.snapshot()), state.query)
        }
        wasPaused = nowPaused
        if (!nowPaused && state.follow) panel.scrollToBottom()
        aggregator.refresh()
    }

    private fun onFilterUpdate(update: LogcatFilterUpdate) {
        if (controller.state.value.pauseState is LogcatPauseState.Paused) return
        applyFilterUpdate(update, controller.state.value.query)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun applyFilterUpdate(update: LogcatFilterUpdate, query: String) {
        val batch = when (update) {
            is LogcatFilterUpdate.Reset -> LogcatRenderBatchFactory.fromSnapshot(
                LogcatBufferSnapshot(update.entries, totalBytes = 0, cursor = LogcatBufferCursor(0, 0)),
                matchSpansFor(update.entries, query),
            )

            is LogcatFilterUpdate.Delta -> LogcatRenderBatchFactory.fromDelta(
                LogcatBufferDelta(
                    entries = update.appended,
                    cursor = LogcatBufferCursor(0, 0),
                    resetRequired = false,
                    oldestRetainedSequence = update.oldestRetainedSequence,
                ),
                matchSpansFor(update.appended, query),
            )
        }
        edtBatcher.submit(batch)
    }

    private fun matchSpansFor(entries: List<SequencedLogcatEntry>, query: String): Map<Long, List<LogcatMatchSpan>> {
        if (query.isBlank()) return emptyMap()
        return entries
            .mapNotNull { sequenced ->
                val spans = LogcatMatchSpanCalculator.spansFor(LogcatRenderBatchFactory.messageTextFor(sequenced.entry), query)
                if (spans.isEmpty()) null else sequenced.sequence to spans
            }
            .toMap()
    }

    override fun dispose() {
        badgeRegistration.unregister()
        edtBatcher.dispose()
        scope.cancel()
        panel.disposePanel()
    }
}
