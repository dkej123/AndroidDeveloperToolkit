package dev.acme.adbtoolbox.domain.capture

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test

class TimestampFileNamePolicyTest {

    private val utc = TimeZone.UTC

    @Test
    fun `builds a screen-prefixed, zero-padded, PNG-suffixed base file name`() {
        val policy = TimestampFileNamePolicy(zone = utc)

        val name = policy.baseFileName(Instant.parse("2026-01-05T03:07:09Z"))

        name shouldBe "screen-20260105-030709.png"
    }

    @Test
    fun `two captures a second apart produce two distinct base file names`() {
        val policy = TimestampFileNamePolicy(zone = utc)

        val first = policy.baseFileName(Instant.parse("2026-09-02T10:15:30Z"))
        val second = policy.baseFileName(Instant.parse("2026-09-02T10:15:31Z"))

        first shouldBe "screen-20260902-101530.png"
        second shouldBe "screen-20260902-101531.png"
    }

    @Test
    fun `a caller-supplied extension overrides the PNG default, for task 020's mp4 recordings`() {
        val policy = TimestampFileNamePolicy(zone = utc, extension = "mp4")

        val name = policy.baseFileName(Instant.parse("2026-01-05T03:07:09Z"))

        name shouldBe "screen-20260105-030709.mp4"
    }
}
