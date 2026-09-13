package dev.acme.adbtoolbox.domain.logcat

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Covers task 035's TDD plan step 3: paused/unseen/jump-latest state derived purely from sequence
 * numbers, including the "honest under eviction" acceptance criterion — pausing never stops
 * ingestion (the buffer/session keep running independently), so the tracker must never report an
 * unseen count larger than what the buffer can still actually show.
 */
class LogcatPauseTest {

    @Test
    fun `resumed state reports no unseen lines regardless of sequence activity`() {
        val state = LogcatPauseState.Resumed

        LogcatPauseTransitions.unseen(state, latestSequence = 500, oldestRetainedSequence = 1) shouldBe
            LogcatUnseenState(paused = false, unseenCount = 0, viewTruncated = false)
    }

    @Test
    fun `pausing at the current sequence starts unseen counting from that point`() {
        val paused = LogcatPauseTransitions.pause(LogcatPauseState.Resumed, atSequence = 10)

        paused.shouldBeInstanceOf<LogcatPauseState.Paused>()
        (paused as LogcatPauseState.Paused).pausedAtSequence shouldBe 10
        LogcatPauseTransitions.unseen(paused, latestSequence = 10, oldestRetainedSequence = 1).unseenCount shouldBe 0
    }

    @Test
    fun `pausing again while already paused does not move the pause point`() {
        val paused = LogcatPauseTransitions.pause(LogcatPauseState.Resumed, atSequence = 10)

        val stillPaused = LogcatPauseTransitions.pause(paused, atSequence = 25)

        (stillPaused as LogcatPauseState.Paused).pausedAtSequence shouldBe 10
    }

    @Test
    fun `unseen count grows with every new sequence while paused and buffer is not evicting`() {
        val paused = LogcatPauseState.Paused(pausedAtSequence = 10)

        val unseen = LogcatPauseTransitions.unseen(paused, latestSequence = 244, oldestRetainedSequence = 1)

        unseen shouldBe LogcatUnseenState(paused = true, unseenCount = 234, viewTruncated = false)
    }

    @Test
    fun `unseen count remains honest when eviction removes entries newer than the pause point`() {
        // Paused at 10; the buffer has since evicted everything before sequence 15 (5 of the
        // "unseen" entries, 11-14, are gone) while still retaining 15..20.
        val paused = LogcatPauseState.Paused(pausedAtSequence = 10)

        val unseen = LogcatPauseTransitions.unseen(paused, latestSequence = 20, oldestRetainedSequence = 15)

        unseen shouldBe LogcatUnseenState(paused = true, unseenCount = 6, viewTruncated = true)
    }

    @Test
    fun `unseen count never goes negative when eviction has removed the entire unseen window`() {
        val paused = LogcatPauseState.Paused(pausedAtSequence = 10)

        val unseen = LogcatPauseTransitions.unseen(paused, latestSequence = 10, oldestRetainedSequence = 50)

        unseen.unseenCount shouldBe 0
        unseen.viewTruncated shouldBe true
    }

    @Test
    fun `view is not reported as truncated when eviction has not reached the pause point`() {
        val paused = LogcatPauseState.Paused(pausedAtSequence = 10)

        LogcatPauseTransitions.unseen(paused, latestSequence = 12, oldestRetainedSequence = 10).viewTruncated shouldBe false
        LogcatPauseTransitions.unseen(paused, latestSequence = 12, oldestRetainedSequence = 11).viewTruncated shouldBe false
    }

    @Test
    fun `jump to latest resets the unseen window to zero while remaining paused`() {
        val paused = LogcatPauseState.Paused(pausedAtSequence = 10)

        val jumped = LogcatPauseTransitions.jumpToLatest(paused, latestSequence = 244)

        jumped shouldBe LogcatPauseState.Paused(pausedAtSequence = 244)
        LogcatPauseTransitions.unseen(jumped, latestSequence = 244, oldestRetainedSequence = 1).unseenCount shouldBe 0
    }

    @Test
    fun `jump to latest while resumed is a no-op`() {
        LogcatPauseTransitions.jumpToLatest(LogcatPauseState.Resumed, latestSequence = 244) shouldBe LogcatPauseState.Resumed
    }

    @Test
    fun `resume clears the paused state entirely`() {
        LogcatPauseTransitions.resume() shouldBe LogcatPauseState.Resumed
    }
}
