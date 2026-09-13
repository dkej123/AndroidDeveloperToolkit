package dev.acme.adbtoolbox.domain.logcat

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Covers task 035's TDD plan step 1: pure severity/search filter composition over immutable
 * [LogcatEntry] values, with no reliance on mutable/shared state.
 */
class LogcatEntryFilterTest {

    @Test
    fun `default criteria matches every entry`() {
        LogcatEntryFilter.matches(record(LogSeverity.VERBOSE, "Sample", "hello"), LogcatFilterCriteria()) shouldBe true
        LogcatEntryFilter.matches(daemonMarker(), LogcatFilterCriteria()) shouldBe true
        LogcatEntryFilter.matches(malformed(), LogcatFilterCriteria()) shouldBe true
    }

    @Test
    fun `minimum severity excludes records below the threshold`() {
        val criteria = LogcatFilterCriteria(minSeverity = LogSeverity.WARN)

        LogcatEntryFilter.matches(record(LogSeverity.INFO, "Sample", "info line"), criteria) shouldBe false
        LogcatEntryFilter.matches(record(LogSeverity.WARN, "Sample", "warn line"), criteria) shouldBe true
        LogcatEntryFilter.matches(record(LogSeverity.ERROR, "Sample", "error line"), criteria) shouldBe true
    }

    @Test
    fun `a severity floor excludes daemon markers and malformed lines, which carry no severity`() {
        val criteria = LogcatFilterCriteria(minSeverity = LogSeverity.VERBOSE)

        LogcatEntryFilter.matches(daemonMarker(), criteria) shouldBe false
        LogcatEntryFilter.matches(malformed(), criteria) shouldBe false
    }

    @Test
    fun `text query matches case-insensitively against tag and message`() {
        val criteria = LogcatFilterCriteria(query = "TIMEOUT")

        LogcatEntryFilter.matches(record(LogSeverity.INFO, "Network", "request timeout after 30s"), criteria) shouldBe true
        LogcatEntryFilter.matches(record(LogSeverity.INFO, "Timeout", "unrelated message"), criteria) shouldBe true
        LogcatEntryFilter.matches(record(LogSeverity.INFO, "Network", "request succeeded"), criteria) shouldBe false
    }

    @Test
    fun `text query also matches continuation lines`() {
        val entry = LogcatEntry.Record(
            record = sampleRecord(LogSeverity.ERROR, "Crash", "top-level message"),
            continuationLines = listOf("at com.acme.Shop.onCreate(Shop.kt:42)"),
        )

        LogcatEntryFilter.matches(entry, LogcatFilterCriteria(query = "onCreate")) shouldBe true
        LogcatEntryFilter.matches(entry, LogcatFilterCriteria(query = "onDestroy")) shouldBe false
    }

    @Test
    fun `text query matches daemon marker and malformed raw text`() {
        LogcatEntryFilter.matches(daemonMarker(), LogcatFilterCriteria(query = "main")) shouldBe true
        LogcatEntryFilter.matches(malformed(), LogcatFilterCriteria(query = "garbled")) shouldBe true
        LogcatEntryFilter.matches(malformed(), LogcatFilterCriteria(query = "nomatch")) shouldBe false
    }

    @Test
    fun `blank query is treated as no search filter`() {
        LogcatEntryFilter.matches(record(LogSeverity.INFO, "Tag", "message"), LogcatFilterCriteria(query = "   ")) shouldBe true
    }

    @Test
    fun `pid filter restricts to records from the resolved package process`() {
        val criteria = LogcatFilterCriteria(pids = setOf(ProcessId(123)))

        LogcatEntryFilter.matches(record(LogSeverity.INFO, "Tag", "mine", pid = 123), criteria) shouldBe true
        LogcatEntryFilter.matches(record(LogSeverity.INFO, "Tag", "not mine", pid = 456), criteria) shouldBe false
    }

    @Test
    fun `a pid filter excludes daemon markers and malformed lines, which carry no pid`() {
        val criteria = LogcatFilterCriteria(pids = setOf(ProcessId(123)))

        LogcatEntryFilter.matches(daemonMarker(), criteria) shouldBe false
        LogcatEntryFilter.matches(malformed(), criteria) shouldBe false
    }

    @Test
    fun `severity, pid, and query filters compose with AND semantics`() {
        val criteria = LogcatFilterCriteria(minSeverity = LogSeverity.WARN, query = "timeout", pids = setOf(ProcessId(123)))

        LogcatEntryFilter.matches(record(LogSeverity.WARN, "Tag", "timeout", pid = 123), criteria) shouldBe true
        LogcatEntryFilter.matches(record(LogSeverity.INFO, "Tag", "timeout", pid = 123), criteria) shouldBe false
        LogcatEntryFilter.matches(record(LogSeverity.WARN, "Tag", "timeout", pid = 456), criteria) shouldBe false
        LogcatEntryFilter.matches(record(LogSeverity.WARN, "Tag", "succeeded", pid = 123), criteria) shouldBe false
    }

    private fun sampleRecord(severity: LogSeverity, tag: String, message: String, pid: Int = 123): LogcatRecord =
        LogcatRecord(
            timestamp = LogTimestamp(9, 10, 12, 34, 56, 789),
            pid = ProcessId(pid),
            tid = ThreadId(456),
            severity = severity,
            tag = LogTag(tag),
            message = LogMessage(message),
        )

    private fun record(severity: LogSeverity, tag: String, message: String, pid: Int = 123): LogcatEntry.Record =
        LogcatEntry.Record(record = sampleRecord(severity, tag, message, pid), continuationLines = emptyList())

    private fun daemonMarker(): LogcatEntry.DaemonMarker =
        LogcatEntry.DaemonMarker(buffer = "main", raw = "--------- beginning of main")

    private fun malformed(): LogcatEntry.Malformed = LogcatEntry.Malformed(raw = "garbled line", reason = "fixture")
}
