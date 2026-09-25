package dev.acme.adbtoolbox.adapters.jvm.diagnostics

import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.concurrent.thread

class FileDiagnosticsLogTest {

    @TempDir
    lateinit var dir: Path

    private val fixedClock = { Instant.parse("2026-09-24T11:18:03.412Z") }

    private fun lines(name: String = "adb-toolbox.log"): List<String> = Files.readAllLines(dir.resolve(name))

    @Test
    fun `writes one structured line per entry with timestamp, level, category, message and fields`() {
        val log = FileDiagnosticsLog(dir, clock = fixedClock)

        log.log(DiagLevel.INFO, "adb", "done", mapOf("transport" to "binary", "ms" to 142, "empty" to null))
        log.close()

        val line = lines().single()
        line shouldStartWith "2026-09-24T11:18:03.412Z INFO  [adb] done transport=binary ms=142"
        line shouldContain "thread="
    }

    @Test
    fun `values with spaces are quoted and newlines are escaped so every entry stays on one line`() {
        val log = FileDiagnosticsLog(dir, clock = fixedClock)

        log.log(DiagLevel.WARN, "process", "finished", mapOf("stderrTail" to "ERROR: a\nERROR: b"))
        log.close()

        lines().single() shouldContain """stderrTail="ERROR: a\nERROR: b""""
    }

    @Test
    fun `an error is appended with its stack trace indented under the entry`() {
        val log = FileDiagnosticsLog(dir, clock = fixedClock)

        log.log(DiagLevel.ERROR, "lifecycle", "boom", error = IllegalStateException("broken"))
        log.close()

        val all = lines()
        all.first() shouldContain "error=java.lang.IllegalStateException: broken"
        all[1] shouldStartWith "    at "
    }

    @Test
    fun `entries below the minimum level are skipped and the level can be changed at runtime`() {
        val log = FileDiagnosticsLog(dir, clock = fixedClock, minLevel = DiagLevel.INFO)

        log.isEnabled(DiagLevel.DEBUG) shouldBe false
        log.log(DiagLevel.DEBUG, "adb", "hidden")
        log.minLevel = DiagLevel.DEBUG
        log.log(DiagLevel.DEBUG, "adb", "visible")
        log.close()

        lines().single() shouldContain "visible"
    }

    @Test
    fun `rotates by size keeping at most maxFiles files`() {
        val log = FileDiagnosticsLog(dir, maxFileBytes = 200, maxFiles = 3, clock = fixedClock)

        repeat(40) { index -> log.log(DiagLevel.INFO, "adb", "entry $index", mapOf("pad" to "x".repeat(40))) }
        log.close()

        Files.list(dir).use { files -> files.map { it.fileName.toString() }.sorted().toList() } shouldContainExactly
            listOf("adb-toolbox.log", "adb-toolbox.log.1", "adb-toolbox.log.2")
        lines().last() shouldContain "entry 39"
    }

    @Test
    fun `a full queue never blocks the caller and the number of dropped entries is reported`() {
        val log = FileDiagnosticsLog(dir, queueCapacity = 1, clock = fixedClock, startWriter = false)

        repeat(5) { log.log(DiagLevel.INFO, "adb", "entry $it") }
        log.startWriter()
        log.close()

        val all = lines()
        all.first() shouldContain "entry 0"
        all.any { it.contains("WARN  [diagnostics] dropped entries") && it.contains("count=4") } shouldBe true
    }

    @Test
    fun `concurrent writers never interleave partial lines`() {
        val log = FileDiagnosticsLog(dir, clock = fixedClock)

        (1..4).map { writer ->
            thread { repeat(250) { log.log(DiagLevel.INFO, "adb", "w$writer-$it") } }
        }.forEach { it.join() }
        log.close()

        val all = lines()
        all.size shouldBe 1000
        all.all { it.startsWith("2026-09-24T11:18:03.412Z INFO  [adb] w") } shouldBe true
    }

    @Test
    fun `flush makes everything logged so far readable`() {
        val log = FileDiagnosticsLog(dir, clock = fixedClock)

        log.log(DiagLevel.INFO, "lifecycle", "started")
        log.flush()

        lines().single() shouldContain "started"
        log.close()
    }
}
