package dev.acme.adbtoolbox.domain.logcat

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class LogcatEntryAssemblerTest {

    @Test
    fun `a header line with no continuation becomes a record with an empty continuation list`() {
        val assembler = LogcatEntryAssembler()

        val duringStream = assembler.accept("09-02 12:00:00.000     1     1 I Tag: message")
        val flushed = assembler.finish()

        duringStream shouldBe emptyList()
        val entry = flushed.single().shouldBeInstanceOf<LogcatEntry.Record>()
        entry.record.message shouldBe LogMessage("message")
        entry.continuationLines shouldBe emptyList()
    }

    @Test
    fun `continuation lines attach to the preceding record rather than becoming standalone entries`() {
        val assembler = LogcatEntryAssembler()

        assembler.accept("09-02 12:00:00.000   789   789 E AndroidRuntime: FATAL EXCEPTION: main")
        assembler.accept("\tat dev.acme.sample.MainActivity.onCreate(MainActivity.kt:42)")
        assembler.accept("\tat android.app.Activity.performCreate(Activity.java:8000)")
        val entries = assembler.finish()

        val entry = entries.single().shouldBeInstanceOf<LogcatEntry.Record>()
        entry.continuationLines shouldBe listOf(
            "\tat dev.acme.sample.MainActivity.onCreate(MainActivity.kt:42)",
            "\tat android.app.Activity.performCreate(Activity.java:8000)",
        )
    }

    @Test
    fun `a new header flushes the previous record with its accumulated continuations`() {
        val assembler = LogcatEntryAssembler()
        val entries = mutableListOf<LogcatEntry>()

        entries += assembler.accept("09-02 12:00:00.000   789   789 E Tag: first")
        entries += assembler.accept("stack frame one")
        entries += assembler.accept("09-02 12:00:00.010   123   456 I Tag: second")
        entries += assembler.finish()

        entries.size shouldBe 2
        val first = entries[0].shouldBeInstanceOf<LogcatEntry.Record>()
        first.record.message shouldBe LogMessage("first")
        first.continuationLines shouldBe listOf("stack frame one")
        val second = entries[1].shouldBeInstanceOf<LogcatEntry.Record>()
        second.record.message shouldBe LogMessage("second")
        second.continuationLines shouldBe emptyList()
    }

    @Test
    fun `a daemon marker flushes the pending record and becomes its own entry`() {
        val assembler = LogcatEntryAssembler()
        val entries = mutableListOf<LogcatEntry>()

        entries += assembler.accept("09-02 12:00:00.000   789   789 E Tag: first")
        entries += assembler.accept("--------- beginning of system")
        entries += assembler.finish()

        entries.size shouldBe 2
        entries[0].shouldBeInstanceOf<LogcatEntry.Record>()
        val marker = entries[1].shouldBeInstanceOf<LogcatEntry.DaemonMarker>()
        marker.buffer shouldBe "system"
    }

    @Test
    fun `a malformed line flushes the pending record and becomes its own malformed entry`() {
        val assembler = LogcatEntryAssembler()
        val entries = mutableListOf<LogcatEntry>()

        entries += assembler.accept("09-02 12:00:00.000   789   789 E Tag: first")
        entries += assembler.accept("99-99 99:99:99.999   1   1 I Tag: bad timestamp")
        entries += assembler.finish()

        entries.size shouldBe 2
        entries[0].shouldBeInstanceOf<LogcatEntry.Record>()
        entries[1].shouldBeInstanceOf<LogcatEntry.Malformed>()
    }

    @Test
    fun `an orphan continuation with no preceding record is preserved as malformed, never dropped`() {
        val assembler = LogcatEntryAssembler()

        val entries = assembler.accept("stray continuation with nothing before it")

        val malformed = entries.single().shouldBeInstanceOf<LogcatEntry.Malformed>()
        malformed.raw shouldBe "stray continuation with nothing before it"
    }

    @Test
    fun `finish on an assembler with nothing pending returns no entries`() {
        val assembler = LogcatEntryAssembler()

        assembler.finish() shouldBe emptyList()
    }
}
