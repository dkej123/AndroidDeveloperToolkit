package dev.acme.adbtoolbox.domain.time

import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class FakeMonotonicClockTest {

    @Test
    fun `starts at zero by default and never advances on its own`() {
        val clock = FakeMonotonicClock()

        clock.markNow() shouldBe 0.seconds
        clock.markNow() shouldBe 0.seconds
    }

    @Test
    fun `advanceBy accumulates rather than replaces the current mark`() {
        val clock = FakeMonotonicClock()

        clock.advanceBy(30.seconds)
        clock.advanceBy(12.seconds)

        clock.markNow() shouldBe 42.seconds
    }

    @Test
    fun `can start at a caller-supplied initial mark`() {
        val clock = FakeMonotonicClock(initial = 5.seconds)

        clock.markNow() shouldBe 5.seconds
    }
}
