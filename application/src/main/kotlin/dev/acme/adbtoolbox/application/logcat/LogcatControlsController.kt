package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.selectedSerialOrNull
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.logcat.LogcatBufferCursor
import dev.acme.adbtoolbox.domain.logcat.LogcatControlsPersistence
import dev.acme.adbtoolbox.domain.logcat.LogcatFilterCriteria
import dev.acme.adbtoolbox.domain.logcat.LogcatFilterEngine
import dev.acme.adbtoolbox.domain.logcat.LogcatPauseState
import dev.acme.adbtoolbox.domain.logcat.LogcatPauseTransitions
import dev.acme.adbtoolbox.domain.logcat.LogcatPersistedControls
import dev.acme.adbtoolbox.domain.logcat.LogcatPidResolution
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.domain.logcat.LogcatSessionError
import dev.acme.adbtoolbox.domain.logcat.LogcatSessionState
import dev.acme.adbtoolbox.domain.logcat.LogcatUnseenState
import dev.acme.adbtoolbox.domain.logcat.ProcessId
import dev.acme.adbtoolbox.domain.logcat.SequencedLogcatEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private sealed interface LogcatWork {
    data object PublicationTick : LogcatWork
    data class CriteriaChanged(val criteria: LogcatFilterCriteria) : LogcatWork
}

/**
 * Task 037's Logcat controls reducer: search/level/package-filter/wrap/pause/follow/clear/footer
 * state, driven by task 034's [sessionManager] and task 035's [pidTracker]/[filterEngine], with the
 * three persisted fields (`logMinLevel`, `logPackageFilterOn`, `logWrap`) round-tripped through
 * [persistence]. Contains no parser/PID/session logic of its own (that stays owned by tasks
 * 033-035) — this only reduces control state and republishes already-parsed
 * [SequencedLogcatEntry]s through [filterUpdates] for a presentation layer to turn into render rows.
 *
 * Every [sessionManager]/[pidTracker]/device/package collector and every [handle] intent that
 * touches [filterEngine] or the sequence/cursor bookkeeping is funneled through the single
 * [workItems] consumer loop, so [filterEngine] (a plain, non-thread-safe class) is only ever
 * mutated from one coroutine at a time — mirroring
 * [dev.acme.adbtoolbox.application.network.ProxyController]'s single-consumer-channel shape, minus
 * that controller's cross-item cancellation (a full [LogcatFilterEngine.setCriteria] rescan is
 * cooperatively cancellable by design, but this controller processes work items strictly in order
 * rather than abandoning a stale one — a deliberate simplification within task 037's scope).
 *
 * [follow] (autoscroll) and [LogcatPauseState] are deliberately independent (`design/README.md`'s
 * paused pill is "shown whenever paused OR autoscroll is off", with different pill text for each):
 * [followAnchor] reuses [LogcatPauseState]/[LogcatPauseTransitions.unseen] purely as an
 * implementation trick to derive an honest "N new lines below" count for the un-followed-but-not-
 * paused case, without duplicating that eviction-aware clamping logic.
 */
class LogcatControlsController(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val selectedPackageState: StateFlow<SelectedPackageState>,
    private val sessionManager: LogcatSessionManager,
    private val pidTracker: LogcatPackagePidTracker,
    private val persistence: LogcatControlsPersistence,
    private val filterEngine: LogcatFilterEngine = LogcatFilterEngine(),
) {
    private val _state = MutableStateFlow(LogcatControlsState())
    val state: StateFlow<LogcatControlsState> = _state.asStateFlow()

    private val filterUpdatesChannel = Channel<LogcatFilterUpdate>(Channel.UNLIMITED)

    /** Publishes [LogcatFilterUpdate]s as they happen; a late subscriber should seed itself with
     * [snapshot] first (mirrors a cold subscriber needing an initial [LogcatFilterUpdate.Reset]). */
    val filterUpdates: Flow<LogcatFilterUpdate> = filterUpdatesChannel.receiveAsFlow()

    private val workItems = Channel<LogcatWork>(Channel.UNLIMITED)

    private var criteria = LogcatFilterCriteria()
    private var currentSerial: DeviceSerial? = null
    private var latestSequence = 0L
    private var oldestRetainedSequence: Long? = null
    private var pauseState: LogcatPauseState = LogcatPauseState.Resumed
    private var follow = true
    private var followAnchor: LogcatPauseState = LogcatPauseState.Resumed
    private var clearedFlag = false
    private var minSeverity: LogSeverity? = null
    private var packageFilterOn = true
    private var wrap = false
    private var packageFilterLabel: String? = null

    init {
        scope.launch(dispatchers.io) {
            val persisted = runCatching { persistence.read() }.getOrDefault(LogcatPersistedControls())
            applyPersisted(persisted)
        }
        scope.launch(dispatchers.default) { selectedDeviceState.collect(::onDeviceStateChanged) }
        scope.launch(dispatchers.default) { selectedPackageState.collect(::onPackageStateChanged) }
        scope.launch(dispatchers.default) { pidTracker.state.collect { recomputePids() } }
        scope.launch(dispatchers.default) { sessionManager.state.collect(::onSessionStateChanged) }
        scope.launch(dispatchers.default) {
            sessionManager.publication.collect { workItems.trySend(LogcatWork.PublicationTick) }
        }
        scope.launch(dispatchers.default) {
            for (item in workItems) processWork(item)
        }
    }

    /** The filtered view as of right now — a fresh consumer's seed before subscribing to [filterUpdates]. */
    fun snapshot(): List<SequencedLogcatEntry> = filterEngine.snapshot()

    fun handle(intent: LogcatControlsIntent) {
        when (intent) {
            is LogcatControlsIntent.SetQuery -> setQuery(intent.text)
            is LogcatControlsIntent.SetMinSeverity -> setMinSeverity(intent.level)
            LogcatControlsIntent.TogglePackageFilter -> togglePackageFilter()
            LogcatControlsIntent.ToggleWrap -> toggleWrap()
            LogcatControlsIntent.TogglePause -> togglePause()
            LogcatControlsIntent.ManualScrollAway -> turnFollowOff()
            LogcatControlsIntent.ToggleFollow -> toggleFollow()
            LogcatControlsIntent.JumpToLatest -> jumpToLatest()
            LogcatControlsIntent.ClearLocal -> clearLocal()
            LogcatControlsIntent.ResetFilters -> resetFilters()
        }
    }

    private fun applyPersisted(persisted: LogcatPersistedControls) {
        minSeverity = persisted.minSeverity
        packageFilterOn = persisted.packageFilterOn
        wrap = persisted.wrap
        criteria = criteria.copy(minSeverity = minSeverity, pids = resolvePids())
        publishControlState()
        workItems.trySend(LogcatWork.CriteriaChanged(criteria))
    }

    private fun setQuery(text: String) {
        criteria = criteria.copy(query = text)
        publishControlState { it.copy(query = text) }
        workItems.trySend(LogcatWork.CriteriaChanged(criteria))
    }

    private fun setMinSeverity(level: LogSeverity?) {
        minSeverity = level
        criteria = criteria.copy(minSeverity = level)
        publishControlState { it.copy(minSeverity = level) }
        workItems.trySend(LogcatWork.CriteriaChanged(criteria))
        persist()
    }

    private fun togglePackageFilter() {
        packageFilterOn = !packageFilterOn
        recomputePids()
        persist()
    }

    private fun toggleWrap() {
        wrap = !wrap
        publishControlState { it.copy(wrap = wrap) }
        persist()
    }

    private fun togglePause() {
        pauseState = if (pauseState is LogcatPauseState.Paused) {
            LogcatPauseTransitions.resume()
        } else {
            LogcatPauseTransitions.pause(pauseState, latestSequence)
        }
        publishUnseenOnly()
    }

    private fun turnFollowOff() {
        if (!follow) return
        follow = false
        followAnchor = LogcatPauseState.Paused(latestSequence)
        publishUnseenOnly()
    }

    private fun toggleFollow() {
        if (follow) {
            turnFollowOff()
        } else {
            follow = true
            followAnchor = LogcatPauseState.Resumed
            publishUnseenOnly()
        }
    }

    private fun jumpToLatest() {
        follow = true
        followAnchor = LogcatPauseState.Resumed
        pauseState = LogcatPauseTransitions.resume()
        publishUnseenOnly()
    }

    private fun clearLocal() {
        clearedFlag = true
        publishControlState { it.copy(visibleCount = 0, totalRetainedCount = 0, cleared = true) }
        scope.launch(dispatchers.io) { sessionManager.clear() }
    }

    private fun resetFilters() {
        minSeverity = null
        packageFilterOn = false
        criteria = criteria.copy(minSeverity = null, query = "", pids = null)
        publishControlState { it.copy(minSeverity = null, packageFilterOn = false, query = "") }
        workItems.trySend(LogcatWork.CriteriaChanged(criteria))
        persist()
    }

    private fun recomputePids() {
        val pids = resolvePids()
        publishControlState()
        if (pids == criteria.pids) return
        criteria = criteria.copy(pids = pids)
        workItems.trySend(LogcatWork.CriteriaChanged(criteria))
    }

    private fun resolvePids(): Set<ProcessId>? {
        if (!packageFilterOn) return null
        return when (val resolution = pidTracker.state.value) {
            is LogcatPidResolution.Resolved -> resolution.pids
            LogcatPidResolution.NoProcess -> emptySet()
            LogcatPidResolution.NotResolved, is LogcatPidResolution.Malformed, is LogcatPidResolution.Failed -> null
        }
    }

    private fun onDeviceStateChanged(deviceState: SelectedDeviceState) {
        val serial = deviceState.selectedSerialOrNull
        if (serial != currentSerial) {
            currentSerial = serial
            pauseState = LogcatPauseState.Resumed
            follow = true
            followAnchor = LogcatPauseState.Resumed
            clearedFlag = false
        }
        val eligible = deviceState.toCommandContext() is DeviceCommandContext.Eligible
        publishControlState { it.copy(serial = serial, isDeviceEligible = eligible) }
    }

    private fun onPackageStateChanged(packageState: SelectedPackageState) {
        packageFilterLabel = (packageState as? SelectedPackageState.Selected)?.selection?.packageName
        publishControlState { it.copy(packageFilterLabel = packageFilterLabel) }
    }

    private fun onSessionStateChanged(sessionState: LogcatSessionState) {
        val error = (sessionState as? LogcatSessionState.Error)?.let { renderSessionError(it.error) }
        publishControlState { it.copy(sessionState = sessionState, error = error) }
    }

    private suspend fun processWork(item: LogcatWork) {
        when (item) {
            LogcatWork.PublicationTick -> processPublicationTick()
            is LogcatWork.CriteriaChanged -> processCriteriaChanged(item.criteria)
        }
    }

    private suspend fun processPublicationTick() {
        val delta = sessionManager.buffer.deltaAfter(currentCursorOrNull())
        latestSequence = delta.cursor.sequence
        oldestRetainedSequence = delta.oldestRetainedSequence
        cursorGeneration = delta.cursor.generation
        cursorSequence = delta.cursor.sequence
        if (delta.resetRequired) {
            val filtered = filterEngine.setCriteria(criteria, delta.entries)
            emitUpdate(LogcatFilterUpdate.Reset(filtered))
        } else {
            val appended = filterEngine.applyDelta(delta.entries)
            delta.oldestRetainedSequence?.let(filterEngine::evictBefore)
            emitUpdate(LogcatFilterUpdate.Delta(appended, delta.oldestRetainedSequence))
        }
        val publication = sessionManager.publication.value
        if (publication.entryCount > 0) clearedFlag = false
        publishCounts(publication.totalBytes, publication.entryCount)
    }

    private suspend fun processCriteriaChanged(newCriteria: LogcatFilterCriteria) {
        criteria = newCriteria
        val snapshot = sessionManager.buffer.snapshot()
        cursorGeneration = snapshot.cursor.generation
        cursorSequence = snapshot.cursor.sequence
        latestSequence = snapshot.cursor.sequence
        oldestRetainedSequence = snapshot.entries.firstOrNull()?.sequence
        val filtered = filterEngine.setCriteria(criteria, snapshot.entries)
        emitUpdate(LogcatFilterUpdate.Reset(filtered))
        publishCounts(snapshot.totalBytes, snapshot.entries.size)
    }

    private var cursorGeneration: Long? = null
    private var cursorSequence: Long = 0L

    private fun currentCursorOrNull() = cursorGeneration?.let { LogcatBufferCursor(it, cursorSequence) }

    private fun emitUpdate(update: LogcatFilterUpdate) {
        filterUpdatesChannel.trySend(update)
    }

    private fun publishCounts(totalBytes: Int, totalRetainedCount: Int) {
        publishControlState {
            it.copy(
                visibleCount = filterEngine.filteredCount,
                totalRetainedCount = totalRetainedCount,
                totalBytes = totalBytes,
                unseen = computeUnseen(),
                cleared = clearedFlag,
            )
        }
    }

    private fun publishUnseenOnly() {
        publishControlState {
            it.copy(pauseState = pauseState, follow = follow, unseen = computeUnseen())
        }
    }

    private fun computeUnseen(): LogcatUnseenState {
        val pausedUnseen = LogcatPauseTransitions.unseen(pauseState, latestSequence, oldestRetainedSequence)
        if (pausedUnseen.paused) return pausedUnseen
        if (follow) return LogcatUnseenState(paused = false, unseenCount = 0, viewTruncated = false)
        return LogcatPauseTransitions.unseen(followAnchor, latestSequence, oldestRetainedSequence).copy(paused = false)
    }

    private fun renderSessionError(error: LogcatSessionError): String = when (error) {
        is LogcatSessionError.StartFailure -> error.reason
        is LogcatSessionError.UnexpectedExit -> "logcat exited unexpectedly (exit ${error.exitCode})"
        is LogcatSessionError.StreamFailure -> error.reason
        is LogcatSessionError.TimedOut -> "Timed out"
    }

    private fun persist() {
        val toPersist = LogcatPersistedControls(minSeverity, packageFilterOn, wrap)
        scope.launch(dispatchers.io) {
            try {
                persistence.write(toPersist)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Best-effort local save (task 037): a failed persistence write is not surfaced as a
                // user-facing error — the in-memory control state itself is already correct.
            }
        }
    }

    private inline fun publishControlState(mutate: (LogcatControlsState) -> LogcatControlsState = { it }) {
        _state.value = mutate(
            _state.value.copy(
                minSeverity = minSeverity,
                packageFilterOn = packageFilterOn,
                wrap = wrap,
                pauseState = pauseState,
                follow = follow,
            ),
        )
    }
}
