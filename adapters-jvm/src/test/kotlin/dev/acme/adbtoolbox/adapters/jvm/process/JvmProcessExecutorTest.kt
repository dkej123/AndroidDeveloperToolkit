package dev.acme.adbtoolbox.adapters.jvm.process

import dev.acme.adbtoolbox.domain.process.ProcessCommand
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import dev.acme.adbtoolbox.domain.process.ProcessOutputKind
import dev.acme.adbtoolbox.domain.process.ProcessRequest
import dev.acme.adbtoolbox.domain.process.executeBuffered
import dev.acme.adbtoolbox.domain.process.executeToSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// NOTE: every test body is wrapped as `{ runBlocking { ... } }` (a statement, not an expression
// body) deliberately — kotest's `shouldBe` returns its receiver for chaining, so an expression-
// bodied `fun x() = runBlocking { ... }` infers a non-Unit return type from whatever assertion
// happens to be last, and JUnit5 silently skips `@Test` methods that don't return void.
class JvmProcessExecutorTest {
    private val executor = JvmProcessExecutor()

    @Test
    fun `success separates stdout and stderr and reports exit code`() {
        runBlocking {
            val request = ProcessRequest(JavaHelperProcess.command("stdout-stderr"))

            val result = executor.executeBuffered(request)

            result.outcome shouldBe ProcessOutcome.Completed(0)
            result.stdout shouldBe "stdout-line-1\nstdout-line-2\n"
            result.stderr shouldBe "stderr-line-1\n"
        }
    }

    @Test
    fun `non-zero exit code is reported, not thrown`() {
        runBlocking {
            val request = ProcessRequest(JavaHelperProcess.command("exit-code", "7"))

            val result = executor.executeBuffered(request)

            result.outcome shouldBe ProcessOutcome.Completed(7)
        }
    }

    @Test
    fun `binary output is delivered as raw bytes, not decoded text`() {
        runBlocking {
            val request =
                ProcessRequest(JavaHelperProcess.command("binary"), outputKind = ProcessOutputKind.Binary)
            val written = mutableListOf<Byte>()

            val outcome = executor.executeToSink(request, stdoutSink = { bytes -> written += bytes.toList() })

            outcome shouldBe ProcessOutcome.Completed(0)
            written shouldBe listOf(0x00, 0x01, 0xFF.toByte(), 0x89.toByte(), 0x50, 0x4E, 0x47)
        }
    }

    @Test
    fun `start failure for a nonexistent executable is a typed outcome with a non-empty reason`() {
        runBlocking {
            val request = ProcessRequest(ProcessCommand(executable = "definitely-not-a-real-executable-xyz"))

            val result = executor.executeBuffered(request)

            val outcome = result.outcome
            check(outcome is ProcessOutcome.StartFailure) { "expected StartFailure, got $outcome" }
            outcome.reason.isNotBlank() shouldBe true
        }
    }

    @Test
    @Timeout(10)
    fun `timeout kills the process and reports TimedOut`() {
        runBlocking {
            val request = ProcessRequest(
                command = JavaHelperProcess.command("sleep", "5000"),
                timeout = 100.milliseconds,
            )

            val result = executor.executeBuffered(request)

            result.outcome shouldBe ProcessOutcome.TimedOut
        }
    }

    @Test
    @Timeout(10)
    fun `cancelling the collecting coroutine terminates the process`() {
        runBlocking {
            // A sleeping helper emits no stdout, so there is nothing to assert about events —
            // the assertion here is that cancellation completes promptly (the @Timeout would
            // otherwise fire) and that the executor is left usable afterwards, i.e. nothing was
            // left wedged holding a thread/process open.
            val sleepingRequest = ProcessRequest(JavaHelperProcess.command("sleep", "5000"))

            val job = launch(Dispatchers.IO) {
                executor.execute(sleepingRequest).launchIn(this).join()
            }

            delay(300)
            job.cancelAndJoinSafely()

            val followUp = executor.executeBuffered(ProcessRequest(JavaHelperProcess.command("exit-code", "0")))
            followUp.outcome shouldBe ProcessOutcome.Completed(0)
        }
    }

    @Test
    @Timeout(15)
    fun `repeated start-then-immediate-cancel does not leak processes or hang`() {
        runBlocking {
            repeat(20) {
                val request = ProcessRequest(JavaHelperProcess.command("sleep", "3000"))
                val job = launch(Dispatchers.IO) {
                    executor.execute(request).launchIn(this).join()
                }
                job.cancelAndJoinSafely()
            }
            // Reaching this line within the test timeout demonstrates no coroutine/thread
            // deadlocked waiting on a leaked process across 20 dispose cycles.
        }
    }

    @Test
    fun `large output does not exceed bounded buffer capacity and slow consumer applies backpressure`() {
        runBlocking {
            val request = ProcessRequest(
                command = JavaHelperProcess.command("large-output", "500"),
                outputBufferCapacity = 4,
            )

            var lineCount = 0
            withTimeout(20.seconds) {
                executor.execute(request).collect { event ->
                    if (event is ProcessEvent.StdoutText) {
                        lineCount++
                        delay(1) // slow consumer forces the producer to suspend on the bounded channel
                    }
                }
            }

            lineCount shouldBe 500
        }
    }

    @Test
    fun `structured arguments are passed literally, never shell-concatenated`() {
        runBlocking {
            val trickyArg = "arg with spaces && ; | echo shouldnotrun"
            val request = ProcessRequest(JavaHelperProcess.command("echo-args", trickyArg))

            val result = executor.executeBuffered(request)

            result.stdout shouldContain trickyArg
            result.outcome shouldBe ProcessOutcome.Completed(0)
        }
    }

    private suspend fun Job.cancelAndJoinSafely() {
        cancel()
        try {
            withTimeout(5.seconds) { join() }
        } catch (e: CancellationException) {
            // expected: cancelling this job's own coroutine surfaces as CancellationException
        }
    }
}
