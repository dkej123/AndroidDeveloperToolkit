package dev.acme.adbtoolbox.domain.logcat

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Covers task 035's TDD plan step 2: parsing `pidof` output into a [LogcatPidResolution], including
 * no-process, single, multiple, and malformed outputs.
 */
class LogcatPidParserTest {

    @Test
    fun `empty output means the package has no running process`() {
        LogcatPidParser.parse("") shouldBe LogcatPidResolution.NoProcess
        LogcatPidParser.parse("\n") shouldBe LogcatPidResolution.NoProcess
        LogcatPidParser.parse("   ") shouldBe LogcatPidResolution.NoProcess
    }

    @Test
    fun `a single pid resolves to exactly one process id`() {
        LogcatPidParser.parse("1234\n") shouldBe LogcatPidResolution.Resolved(setOf(ProcessId(1234)))
    }

    @Test
    fun `multiple space-separated pids all resolve`() {
        LogcatPidParser.parse("1234 5678") shouldBe LogcatPidResolution.Resolved(setOf(ProcessId(1234), ProcessId(5678)))
    }

    @Test
    fun `multiple pids separated by repeated whitespace still resolve`() {
        LogcatPidParser.parse("1234   5678\n") shouldBe LogcatPidResolution.Resolved(setOf(ProcessId(1234), ProcessId(5678)))
    }

    @Test
    fun `non-numeric output is malformed rather than silently dropped`() {
        val result = LogcatPidParser.parse("pidof: command not found")

        result.shouldBeInstanceOf<LogcatPidResolution.Malformed>()
        (result as LogcatPidResolution.Malformed).raw shouldBe "pidof: command not found"
    }

    @Test
    fun `a partially numeric token is malformed, not silently truncated to the valid pids`() {
        LogcatPidParser.parse("1234 abc").shouldBeInstanceOf<LogcatPidResolution.Malformed>()
    }
}
