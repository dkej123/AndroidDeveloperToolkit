package dev.acme.adbtoolbox.domain.logcat

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

private fun fixture(name: String): List<String> =
    checkNotNull(object {}.javaClass.getResourceAsStream("/logcat/$name")) { "missing fixture $name" }
        .bufferedReader(Charsets.UTF_8)
        .readLines()

class LogcatLineParserTest {

    @Test
    fun `parses every logcat severity including assert via both F and A letters`() {
        val results = fixture("severities.txt").map(LogcatLineParser::parse)

        val severities = results.map { (it as LogcatParseResult.Parsed).record.severity }
        severities shouldBe listOf(
            LogSeverity.VERBOSE,
            LogSeverity.DEBUG,
            LogSeverity.INFO,
            LogSeverity.WARN,
            LogSeverity.ERROR,
            LogSeverity.ASSERT,
            LogSeverity.ASSERT,
        )
    }

    @Test
    fun `parses timestamp, pid, tid, tag and message fields`() {
        val result = LogcatLineParser.parse("09-02 12:34:56.789   123   456 I MyApp   : hello there")

        val parsed = result.shouldBeInstanceOf<LogcatParseResult.Parsed>()
        parsed.record shouldBe LogcatRecord(
            timestamp = LogTimestamp(month = 9, day = 2, hour = 12, minute = 34, second = 56, millisecond = 789),
            pid = ProcessId(123),
            tid = ThreadId(456),
            severity = LogSeverity.INFO,
            tag = LogTag("MyApp"),
            message = LogMessage("hello there"),
        )
    }

    @Test
    fun `recognizes daemon start-of-buffer markers distinctly from device records`() {
        val results = fixture("daemon-headers.txt").map(LogcatLineParser::parse)

        results[0].shouldBeInstanceOf<LogcatParseResult.DaemonMarker>().buffer shouldBe "main"
        results[1].shouldBeInstanceOf<LogcatParseResult.Parsed>()
        results[2].shouldBeInstanceOf<LogcatParseResult.DaemonMarker>().buffer shouldBe "system"
        results[4].shouldBeInstanceOf<LogcatParseResult.DaemonMarker>().buffer shouldBe "crash"
    }

    @Test
    fun `lines with no header are parsed as continuations`() {
        val results = fixture("continuation.txt").map(LogcatLineParser::parse)

        results[0].shouldBeInstanceOf<LogcatParseResult.Parsed>()
        results.drop(1).dropLast(1).forEach { it.shouldBeInstanceOf<LogcatParseResult.Parsed>() }
    }

    @Test
    fun `handles representative OEM formatting variations`() {
        val results = fixture("oem-variations.txt").map(LogcatLineParser::parse)

        val records = results.map { (it as LogcatParseResult.Parsed).record }
        records[0].tag shouldBe LogTag("VendorTag")
        records[0].message shouldBe LogMessage("message with no space after the colon")
        records[1].pid shouldBe ProcessId(7)
        records[1].tid shouldBe ThreadId(8)
        records[2].tag shouldBe LogTag("tag-with-dashes")
        records[3].message shouldBe LogMessage("")
    }

    @Test
    fun `a line with all-out-of-range timestamp fields is preserved as malformed`() {
        val result = LogcatLineParser.parse(
            "99-99 99:99:99.999   123   456 I MyApp   : timestamp fields all out of range",
        )

        val malformed = result.shouldBeInstanceOf<LogcatParseResult.Malformed>()
        malformed.raw shouldBe "99-99 99:99:99.999   123   456 I MyApp   : timestamp fields all out of range"
    }

    @Test
    fun `a line that looks like a header but has a non-numeric pid is preserved as malformed`() {
        val result = LogcatLineParser.parse("09-02 12:38:00.001   abc   456 I MyApp   : pid is not an integer")

        result.shouldBeInstanceOf<LogcatParseResult.Malformed>()
    }

    @Test
    fun `a truncated header-looking prefix is preserved as malformed rather than a continuation`() {
        val result = LogcatLineParser.parse("09-02 1234:56.789   123   456 I MyApp   : truncated timestamp prefix")

        result.shouldBeInstanceOf<LogcatParseResult.Malformed>()
    }

    @Test
    fun `a plain line with no header-like prefix is a continuation, not malformed`() {
        val result = LogcatLineParser.parse("this is a plain continuation line with no header at all")

        result.shouldBeInstanceOf<LogcatParseResult.Continuation>()
    }

    @Test
    fun `malformed lines never crash the parser and preserve the raw text`() {
        val results = fixture("malformed.txt").map(LogcatLineParser::parse)

        results[0].shouldBeInstanceOf<LogcatParseResult.Malformed>()
        results[1].shouldBeInstanceOf<LogcatParseResult.Malformed>()
        results[2].shouldBeInstanceOf<LogcatParseResult.Malformed>()
        results[3].shouldBeInstanceOf<LogcatParseResult.Continuation>()
        results[4].shouldBeInstanceOf<LogcatParseResult.Parsed>()
    }

    @Test
    fun `parses non-ascii tags and messages including CJK text and emoji`() {
        val results = fixture("non-ascii.txt").map(LogcatLineParser::parse)

        val records = results.map { (it as LogcatParseResult.Parsed).record }
        records[0].tag shouldBe LogTag("日本語タグ")
        records[0].message shouldBe LogMessage("こんにちは世界 😀 emoji and japanese text")
        records[1].message shouldBe LogMessage("Zażółć gęślą jaźń — polskie znaki diakrytyczne")
        records[2].message shouldBe LogMessage("rocket 🚀 fire 🔥 sparkles ✨")
    }

    @Test
    fun `blank lines are treated as continuations rather than dropped`() {
        val result = LogcatLineParser.parse("")

        result.shouldBeInstanceOf<LogcatParseResult.Continuation>()
    }
}
