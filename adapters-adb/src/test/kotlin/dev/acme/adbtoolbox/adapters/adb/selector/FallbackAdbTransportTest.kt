package dev.acme.adbtoolbox.adapters.adb.selector

import dev.acme.adbtoolbox.domain.adb.AdbBinaryScript
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

// NOTE: statement body `{ runBlocking { ... } }`, not an expression body — see BinaryAdbTransportTest.
class FallbackAdbTransportTest {

    private val serial = DeviceSerial.of("R58N90ABCDE")
    private val shellRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("pm clear com.example"))),
    )

    // --- executeText -----------------------------------------------------------------------

    @Test
    fun `primary success is returned untouched and fallback is never invoked`() {
        runBlocking {
            val primaryResult = AdbTextResult(AdbOutcome.Completed(0), stdout = "ok", stderr = "")
            val primary = FakeAdbTransport(textScript = { primaryResult })
            val fallback = FakeAdbTransport(textScript = { error("fallback must not be invoked") })

            val result = FallbackAdbTransport(primary, fallback).executeText(shellRequest)

            result shouldBe primaryResult
            fallback.textRequests shouldBe emptyList()
        }
    }

    @Test
    fun `primary Unsupported before any execution attempt safely falls back to the secondary transport`() {
        runBlocking {
            val fallbackResult = AdbTextResult(AdbOutcome.Completed(0), stdout = "from fallback", stderr = "")
            val primary = FakeAdbTransport(
                textScript = { AdbTextResult(AdbOutcome.Unsupported("no ddmlib equivalent"), "", "") },
            )
            val fallback = FakeAdbTransport(textScript = { fallbackResult })

            val result = FallbackAdbTransport(primary, fallback).executeText(shellRequest)

            result shouldBe fallbackResult
            fallback.textRequests shouldContainExactly listOf(shellRequest)
        }
    }

    @Test
    fun `ambiguous mutation TransportFailure is surfaced honestly and never retried on the fallback`() {
        runBlocking {
            val ambiguous = AdbTextResult(AdbOutcome.TransportFailure("connection reset mid-command"), "", "")
            val primary = FakeAdbTransport(textScript = { ambiguous })
            val fallback = FakeAdbTransport(textScript = { error("fallback must never run an ambiguous mutation") })

            val result = FallbackAdbTransport(primary, fallback).executeText(shellRequest)

            result shouldBe ambiguous
            fallback.textRequests shouldBe emptyList()
        }
    }

    @Test
    fun `an elapsed timeout is also ambiguous and is never retried on the fallback`() {
        runBlocking {
            val timedOut = AdbTextResult(AdbOutcome.TimedOut, "", "")
            val primary = FakeAdbTransport(textScript = { timedOut })
            val fallback = FakeAdbTransport(textScript = { error("fallback must never run after a timeout") })

            val result = FallbackAdbTransport(primary, fallback).executeText(shellRequest)

            result shouldBe timedOut
            fallback.textRequests shouldBe emptyList()
        }
    }

    // --- executeStream -----------------------------------------------------------------------

    @Test
    fun `stream Unsupported as the sole event safely falls back and relays the fallback's stream`() {
        runBlocking {
            val primary = FakeAdbTransport(
                streamScript = { listOf(AdbStreamEvent.Completed(AdbOutcome.Unsupported("no ddmlib equivalent"))) },
            )
            val fallbackEvents = listOf(
                AdbStreamEvent.Line("line one"),
                AdbStreamEvent.Completed(AdbOutcome.Completed(0)),
            )
            val fallback = FakeAdbTransport(streamScript = { fallbackEvents })

            val events = FallbackAdbTransport(primary, fallback).executeStream(shellRequest).toList()

            events shouldContainExactly fallbackEvents
            fallback.streamRequests shouldContainExactly listOf(shellRequest)
        }
    }

    @Test
    fun `stream lines followed by an ambiguous failure are relayed untouched with no fallback`() {
        runBlocking {
            val primaryEvents = listOf(
                AdbStreamEvent.Line("line one"),
                AdbStreamEvent.Completed(AdbOutcome.TransportFailure("connection reset mid-stream")),
            )
            val primary = FakeAdbTransport(streamScript = { primaryEvents })
            val fallback = FakeAdbTransport(streamScript = { error("fallback must never run after partial output") })

            val events = FallbackAdbTransport(primary, fallback).executeStream(shellRequest).toList()

            events shouldContainExactly primaryEvents
            fallback.streamRequests shouldBe emptyList()
        }
    }

    // --- executeBinary -----------------------------------------------------------------------

    @Test
    fun `binary Unsupported safely falls back and the sink receives only the fallback's bytes`() {
        runBlocking {
            val primary = FakeAdbTransport(
                binaryScript = { AdbBinaryScript(emptyList(), AdbOutcome.Unsupported("no ddmlib exec-out")) },
            )
            val fallbackBytes = byteArrayOf(1, 2, 3)
            val fallback = FakeAdbTransport(
                binaryScript = { AdbBinaryScript(listOf(fallbackBytes), AdbOutcome.Completed(0)) },
            )
            val received = mutableListOf<Byte>()
            val sink = ByteSink { chunk -> received += chunk.toList() }

            val outcome = FallbackAdbTransport(primary, fallback).executeBinary(shellRequest, sink)

            outcome shouldBe AdbOutcome.Completed(0)
            received shouldContainExactly fallbackBytes.toList()
        }
    }

    @Test
    fun `binary ambiguous failure is surfaced honestly and never retried on the fallback`() {
        runBlocking {
            val ambiguous = AdbOutcome.TransportFailure("connection reset mid-transfer")
            val primary = FakeAdbTransport(
                binaryScript = { AdbBinaryScript(emptyList(), ambiguous) },
            )
            val fallback = FakeAdbTransport(
                binaryScript = { error("fallback must never run an ambiguous mutation") },
            )

            val outcome = FallbackAdbTransport(primary, fallback).executeBinary(shellRequest, ByteSink { })

            outcome shouldBe ambiguous
            fallback.binaryRequests shouldBe emptyList()
        }
    }
}
