package dev.acme.adbtoolbox.domain.logcat

/**
 * Whether the Logcat view is paused (task 035), and if so, the sequence at which it paused.
 * Pausing never stops ingestion — the buffer/session keep appending independently
 * ([dev.acme.adbtoolbox.application.logcat.LogcatSessionManager]) — this only records where the
 * user's frozen view point is, so [LogcatPauseTransitions.unseen] can derive an honest unseen count.
 */
sealed interface LogcatPauseState {
    data object Resumed : LogcatPauseState
    data class Paused(val pausedAtSequence: Long) : LogcatPauseState
}

/**
 * How many lines arrived after the pause point are still visible, per
 * `design/IMPLEMENTATION.md` §7's "Paused · 234 new lines" pill. [viewTruncated] is `true` when
 * buffer eviction has removed entries between the pause point and the oldest currently retained
 * entry — the frozen scrollback itself is now incomplete, not just "some new lines below".
 */
data class LogcatUnseenState(
    val paused: Boolean,
    val unseenCount: Long,
    val viewTruncated: Boolean,
)

/** Pure sequence-number transitions/derivations for [LogcatPauseState] (task 035). */
object LogcatPauseTransitions {

    fun pause(current: LogcatPauseState, atSequence: Long): LogcatPauseState =
        if (current is LogcatPauseState.Paused) current else LogcatPauseState.Paused(atSequence)

    fun resume(): LogcatPauseState = LogcatPauseState.Resumed

    /** Catches the pause point up to [latestSequence], zeroing the unseen count while staying paused. */
    fun jumpToLatest(current: LogcatPauseState, latestSequence: Long): LogcatPauseState = when (current) {
        LogcatPauseState.Resumed -> current
        is LogcatPauseState.Paused -> LogcatPauseState.Paused(latestSequence)
    }

    /**
     * [oldestRetainedSequence] is the buffer's current oldest retained sequence (`null` when
     * empty). The unseen floor is clamped to whatever the buffer can still show — eviction can
     * only ever shrink the honest count, never inflate it beyond what's retained.
     */
    fun unseen(state: LogcatPauseState, latestSequence: Long, oldestRetainedSequence: Long?): LogcatUnseenState =
        when (state) {
            LogcatPauseState.Resumed -> LogcatUnseenState(paused = false, unseenCount = 0, viewTruncated = false)
            is LogcatPauseState.Paused -> {
                val retainedFloor = (oldestRetainedSequence ?: 1L) - 1
                val floor = maxOf(state.pausedAtSequence, retainedFloor)
                val unseenCount = (latestSequence - floor).coerceAtLeast(0)
                val viewTruncated = state.pausedAtSequence < retainedFloor
                LogcatUnseenState(paused = true, unseenCount = unseenCount, viewTruncated = viewTruncated)
            }
        }
}
