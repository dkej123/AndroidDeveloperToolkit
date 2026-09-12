package dev.acme.adbtoolbox.domain.recording

import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class RecordingElapsedFormatterTest {

    @Test
    fun `zero formats as 00-00`() {
        formatElapsed(Duration.ZERO) shouldBe "00:00"
    }

    @Test
    fun `sub-minute values pad seconds to two digits`() {
        formatElapsed(42.seconds) shouldBe "00:42"
    }

    @Test
    fun `exactly 59 seconds stays in the first minute`() {
        formatElapsed(59.seconds) shouldBe "00:59"
    }

    @Test
    fun `exactly 60 seconds rolls over to one minute, zero seconds`() {
        formatElapsed(60.seconds) shouldBe "01:00"
    }

    @Test
    fun `the 3-minute adb screenrecord cap formats exactly`() {
        formatElapsed(3.minutes) shouldBe "03:00"
    }

    @Test
    fun `sub-second precision is truncated, not rounded up`() {
        formatElapsed(59.seconds + 999.milliseconds) shouldBe "00:59"
    }

    @Test
    fun `a negative duration (clock skew guard) clamps to zero rather than rendering a minus sign`() {
        formatElapsed((-5).seconds) shouldBe "00:00"
    }
}
