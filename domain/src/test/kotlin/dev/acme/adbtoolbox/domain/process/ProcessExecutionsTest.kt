package dev.acme.adbtoolbox.domain.process

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ProcessExecutionsTest {

    @Test
    fun `executeBuffered joins stdout and stderr lines separately on success`() = runTest {
        val executor = FakeProcessExecutor {
            listOf(
                ProcessEvent.StdoutText("first"),
                ProcessEvent.StdoutText("second"),
                ProcessEvent.StderrText("warn"),
                ProcessEvent.Completed(ProcessOutcome.Completed(0)),
            )
        }

        val result = executor.executeBuffered(ProcessRequest(ProcessCommand("getprop")))

        result shouldBe ProcessResult(
            outcome = ProcessOutcome.Completed(0),
            stdout = "first\nsecond\n",
            stderr = "warn\n",
            stdoutTruncated = false,
            stderrTruncated = false,
        )
    }

    @Test
    fun `executeBuffered reports a non-zero exit without treating it as truncation`() = runTest {
        val executor = FakeProcessExecutor {
            listOf(
                ProcessEvent.StderrText("no such package"),
                ProcessEvent.Completed(ProcessOutcome.Completed(1)),
            )
        }

        val result = executor.executeBuffered(ProcessRequest(ProcessCommand("pm")))

        result.outcome shouldBe ProcessOutcome.Completed(1)
        result.stderr shouldBe "no such package\n"
        result.stdoutTruncated shouldBe false
    }

    @Test
    fun `executeBuffered truncates stdout independently of stderr once the byte bound is hit`() = runTest {
        val executor = FakeProcessExecutor {
            listOf(
                ProcessEvent.StdoutText("aaaaaaaaaa"),
                ProcessEvent.StdoutText("bbbbbbbbbb"),
                ProcessEvent.StdoutText("cccccccccc"),
                ProcessEvent.StderrText("short"),
                ProcessEvent.Completed(ProcessOutcome.Completed(0)),
            )
        }

        val result = executor.executeBuffered(
            ProcessRequest(ProcessCommand("logcat")),
            maxBytesPerStream = 15,
        )

        result.stdout shouldBe "aaaaaaaaaa\n"
        result.stdoutTruncated shouldBe true
        result.stderr shouldBe "short\n"
        result.stderrTruncated shouldBe false
    }

    @Test
    fun `executeBuffered surfaces start failure with empty output`() = runTest {
        val executor = FakeProcessExecutor {
            listOf(ProcessEvent.Completed(ProcessOutcome.StartFailure("executable not found")))
        }

        val result = executor.executeBuffered(ProcessRequest(ProcessCommand("nonexistent-tool")))

        result.outcome shouldBe ProcessOutcome.StartFailure("executable not found")
        result.stdout shouldBe ""
        result.stderr shouldBe ""
    }

    @Test
    fun `executeBuffered surfaces timeout`() = runTest {
        val executor = FakeProcessExecutor {
            listOf(ProcessEvent.StdoutText("partial"), ProcessEvent.Completed(ProcessOutcome.TimedOut))
        }

        val result = executor.executeBuffered(ProcessRequest(ProcessCommand("logcat")))

        result.outcome shouldBe ProcessOutcome.TimedOut
        result.stdout shouldBe "partial\n"
    }

    @Test
    fun `executeToSink writes binary chunks in order and ignores stderr bytes`() = runTest {
        val chunkOne = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val chunkTwo = byteArrayOf(0x0d, 0x0a, 0x1a, 0x0a)
        val executor = FakeProcessExecutor {
            listOf(
                ProcessEvent.StdoutBytes(chunkOne),
                ProcessEvent.StderrBytes(byteArrayOf(0x01)),
                ProcessEvent.StdoutBytes(chunkTwo),
                ProcessEvent.Completed(ProcessOutcome.Completed(0)),
            )
        }
        val written = mutableListOf<Byte>()

        val outcome = executor.executeToSink(
            ProcessRequest(ProcessCommand("adb"), outputKind = ProcessOutputKind.Binary),
            stdoutSink = ByteSink { bytes -> written += bytes.toList() },
        )

        outcome shouldBe ProcessOutcome.Completed(0)
        written shouldBe (chunkOne.toList() + chunkTwo.toList())
    }

    @Test
    fun `executeBuffered rejects binary requests`() = runTest {
        val executor = FakeProcessExecutor { listOf(ProcessEvent.Completed(ProcessOutcome.Completed(0))) }

        try {
            executor.executeBuffered(ProcessRequest(ProcessCommand("adb"), outputKind = ProcessOutputKind.Binary))
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `executeToSink rejects text requests`() = runTest {
        val executor = FakeProcessExecutor { listOf(ProcessEvent.Completed(ProcessOutcome.Completed(0))) }

        try {
            executor.executeToSink(ProcessRequest(ProcessCommand("adb")), ByteSink {})
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
