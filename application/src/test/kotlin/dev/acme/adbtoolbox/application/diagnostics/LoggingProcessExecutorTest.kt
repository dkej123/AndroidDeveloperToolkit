package dev.acme.adbtoolbox.application.diagnostics

import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.RecordingDiagnosticsLog
import dev.acme.adbtoolbox.domain.process.ProcessCommand
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import dev.acme.adbtoolbox.domain.process.ProcessRequest
import dev.acme.adbtoolbox.domain.time.FakeMonotonicClock
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LoggingProcessExecutorTest {

    private val clock = FakeMonotonicClock()
    private val log = RecordingDiagnosticsLog()
    private val stats = CommandStatsCollector()

    private fun executor(script: (ProcessRequest) -> Flow<ProcessEvent>) =
        LoggingProcessExecutor(object : ProcessExecutor { override fun execute(request: ProcessRequest) = script(request) }, log, clock, stats)

    private fun request(vararg args: String, timeout: kotlin.time.Duration? = 5.seconds) =
        ProcessRequest(ProcessCommand("/sdk/platform-tools/adb", args.toList(), environment = mapOf("ADB" to "/sdk/adb")), timeout = timeout)

    @Test
    fun `passes every event through unchanged and logs a fast success at debug level`() = runTest {
        val events = listOf(ProcessEvent.StdoutText("List of devices attached"), ProcessEvent.Completed(ProcessOutcome.Completed(0)))
        val executor = executor {
            flow {
                clock.advanceBy(40.milliseconds)
                events.forEach { emit(it) }
            }
        }

        executor.execute(request("devices", "-l")).toList() shouldBe events

        val finished = log.inCategory(DiagCategory.PROCESS).last()
        finished.level shouldBe DiagLevel.DEBUG
        finished.fields["command"] shouldBe "adb devices -l"
        finished.fields["outcome"] shouldBe "Completed(exitCode=0)"
        finished.fields["ms"] shouldBe 40L
        finished.fields["stdoutBytes"] shouldBe 24L
    }

    @Test
    fun `a failing command is a warning carrying the tail of its stderr and the full argv`() = runTest {
        val executor = executor {
            flow {
                emit(ProcessEvent.StderrText("ERROR: Could not find any ADB device"))
                emit(ProcessEvent.Completed(ProcessOutcome.Completed(1)))
            }
        }

        executor.execute(request("-s", "R58N", "shell", "wm", "density")).toList()

        val finished = log.inCategory(DiagCategory.PROCESS).last()
        finished.level shouldBe DiagLevel.WARN
        finished.fields["argv"] shouldBe "/sdk/platform-tools/adb -s R58N shell wm density"
        finished.fields["env"] shouldBe "ADB=/sdk/adb"
        finished.fields["stderrTail"].toString() shouldContain "Could not find any ADB device"
    }

    @Test
    fun `a slow success is a warning`() = runTest {
        val executor = executor {
            flow {
                clock.advanceBy(6.seconds)
                emit(ProcessEvent.Completed(ProcessOutcome.Completed(0)))
            }
        }

        executor.execute(request("version")).toList()

        log.inCategory(DiagCategory.PROCESS).last().level shouldBe DiagLevel.WARN
    }

    @Test
    fun `a long-running process without timeout logs its start at info`() = runTest {
        val executor = executor { flow { emit(ProcessEvent.StdoutText("line")) ; awaitCancellation() } }

        executor.execute(request("logcat", timeout = null)).first()

        val start = log.inCategory(DiagCategory.PROCESS).first()
        start.level shouldBe DiagLevel.INFO
        start.message shouldContain "started"
    }

    @Test
    fun `cancellation is logged and rethrown, never swallowed`() = runTest {
        val executor = executor { flow { throw CancellationException("stop") } }

        assertThrows<CancellationException> { executor.execute(request("logcat", timeout = null)).toList() }

        log.inCategory(DiagCategory.PROCESS).map { it.message } shouldContain "cancelled"
    }

    @Test
    fun `every finished command is recorded in the aggregated stats under a serial-free key`() = runTest {
        val executor = executor {
            flow {
                clock.advanceBy(10.milliseconds)
                emit(ProcessEvent.Completed(ProcessOutcome.Completed(0)))
            }
        }

        executor.execute(request("-s", "R58N", "shell", "getprop", "ro.product.model")).toList()
        executor.execute(request("-s", "OTHER", "shell", "getprop", "ro.build.version.sdk")).toList()

        val snapshot = stats.drain().single()
        snapshot.key shouldBe "adb shell getprop"
        snapshot.count shouldBe 2
        snapshot.totalMs shouldBe 20L
    }
}
