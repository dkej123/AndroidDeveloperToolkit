package dev.acme.adbtoolbox.application.capture

import dev.acme.adbtoolbox.domain.adb.AdbBinaryScript
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private val FIXED_TIMESTAMP = Instant.parse("2026-09-02T10:15:30Z")
private val FIXED_CLOCK = object : Clock {
    override fun now(): Instant = FIXED_TIMESTAMP
}
private val FIXED_FILE_NAME = FileNamePolicy { "screen-20260902-101530.png" }
private val SERIAL = DeviceSerial.of("emulator-5554")
private val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03)

class CaptureScreenshotUseCaseTest {

    @Test
    fun `targets the exact serial passed in, as an exec-out screencap request`() = runTest {
        val transport = FakeAdbTransport(binaryScript = { AdbBinaryScript(listOf(PNG_BYTES), AdbOutcome.Completed(0)) })
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        useCase.capture(SERIAL)

        val request = transport.binaryRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        request.operation shouldBe AdbOperation.Exec(listOf("screencap", "-p"))
    }

    @Test
    fun `preserves PNG bytes exactly through the pipeline and commits under the policy's file name`() = runTest {
        val transport = FakeAdbTransport(binaryScript = { AdbBinaryScript(listOf(PNG_BYTES), AdbOutcome.Completed(0)) })
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        val result = useCase.capture(SERIAL)

        result.shouldBeInstanceOf<CaptureScreenshotResult.Success>()
        val target = destination.targets.single()
        target.baseFileName shouldBe "screen-20260902-101530.png"
        target.writtenBytes() shouldBe PNG_BYTES
        target.committed shouldBe true
        target.discarded shouldBe false
    }

    @Test
    fun `preserves bytes exactly across multiple chunks, never merging or reordering them`() = runTest {
        val chunk1 = byteArrayOf(1, 2, 3)
        val chunk2 = byteArrayOf(4, 5, 6, 7)
        val transport = FakeAdbTransport(binaryScript = { AdbBinaryScript(listOf(chunk1, chunk2), AdbOutcome.Completed(0)) })
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        useCase.capture(SERIAL)

        destination.targets.single().writtenBytes() shouldBe (chunk1 + chunk2)
    }

    @Test
    fun `empty output is a failure and discards the partial target`() = runTest {
        val transport = FakeAdbTransport(binaryScript = { AdbBinaryScript(emptyList(), AdbOutcome.Completed(0)) })
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        val result = useCase.capture(SERIAL)

        result.shouldBeInstanceOf<CaptureScreenshotResult.Failure>()
        destination.targets.single().discarded shouldBe true
        destination.targets.single().committed shouldBe false
    }

    @Test
    fun `a non-zero exit code (malformed capture) is a failure and discards the partial target`() = runTest {
        val transport = FakeAdbTransport(
            binaryScript = { AdbBinaryScript(listOf(byteArrayOf(1, 2, 3)), AdbOutcome.Completed(exitCode = 1)) },
        )
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        val result = useCase.capture(SERIAL)

        result.shouldBeInstanceOf<CaptureScreenshotResult.Failure>()
        destination.targets.single().discarded shouldBe true
    }

    @Test
    fun `a timed-out capture is a failure and discards the partial target`() = runTest {
        val transport = FakeAdbTransport(binaryScript = { AdbBinaryScript(listOf(byteArrayOf(1)), AdbOutcome.TimedOut) })
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        val result = useCase.capture(SERIAL)

        result.shouldBeInstanceOf<CaptureScreenshotResult.Failure>()
        destination.targets.single().discarded shouldBe true
    }

    @Test
    fun `a transport-reported cancellation outcome is a failure and discards the partial target`() = runTest {
        val transport = FakeAdbTransport(binaryScript = { AdbBinaryScript(listOf(byteArrayOf(1)), AdbOutcome.Cancelled) })
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        val result = useCase.capture(SERIAL)

        result.shouldBeInstanceOf<CaptureScreenshotResult.Failure>()
        destination.targets.single().discarded shouldBe true
    }

    @Test
    fun `a transport failure (device offline, unauthorized) is a failure and discards the partial target`() = runTest {
        val transport = FakeAdbTransport(
            binaryScript = { AdbBinaryScript(emptyList(), AdbOutcome.TransportFailure("device offline")) },
        )
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        val result = useCase.capture(SERIAL)

        result.shouldBeInstanceOf<CaptureScreenshotResult.Failure>()
        (result as CaptureScreenshotResult.Failure).reason shouldBe "device offline"
        destination.targets.single().discarded shouldBe true
    }

    @Test
    fun `an unsupported transport outcome is a failure and discards the partial target`() = runTest {
        val transport = FakeAdbTransport(
            binaryScript = { AdbBinaryScript(emptyList(), AdbOutcome.Unsupported("no binary transport")) },
        )
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        val result = useCase.capture(SERIAL)

        result.shouldBeInstanceOf<CaptureScreenshotResult.Failure>()
        destination.targets.single().discarded shouldBe true
    }

    @Test
    fun `a real coroutine cancellation propagates and still discards the partial target`() = runTest {
        val hang = CompletableDeferred<Unit>()
        val transport = object : AdbTransport {
            override suspend fun executeText(request: dev.acme.adbtoolbox.domain.adb.AdbRequest) =
                error("not used")

            override fun executeStream(request: dev.acme.adbtoolbox.domain.adb.AdbRequest): Flow<dev.acme.adbtoolbox.domain.adb.AdbStreamEvent> =
                error("not used")

            override suspend fun executeBinary(request: dev.acme.adbtoolbox.domain.adb.AdbRequest, sink: ByteSink): AdbOutcome {
                sink.write(byteArrayOf(1, 2, 3))
                hang.await()
                error("unreachable: hang never completes")
            }
        }
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        val deferred = async(Job()) { useCase.capture(SERIAL) }
        // Let the use case reach the hanging executeBinary call before cancelling it.
        kotlinx.coroutines.yield()
        deferred.cancel()

        try {
            deferred.await()
        } catch (expected: CancellationException) {
            // expected: cancellation must propagate, never be swallowed as a Failure result.
        }

        destination.targets.single().discarded shouldBe true
        destination.targets.single().committed shouldBe false
    }

    @Test
    fun `the serial captured at call time is the one attributed to the request, independent of any later selection`() = runTest {
        val otherSerial = DeviceSerial.of("emulator-5556")
        val transport = FakeAdbTransport(binaryScript = { AdbBinaryScript(listOf(PNG_BYTES), AdbOutcome.Completed(0)) })
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)

        useCase.capture(SERIAL)
        useCase.capture(otherSerial)

        transport.binaryRequests.map { (it as AdbDeviceRequest).serial } shouldBe listOf(SERIAL, otherSerial)
    }

    @Test
    fun `applies the given timeout to the exec-out request`() = runTest {
        val transport = FakeAdbTransport(binaryScript = { AdbBinaryScript(listOf(PNG_BYTES), AdbOutcome.Completed(0)) })
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK, timeout = 7.seconds)

        useCase.capture(SERIAL)

        transport.binaryRequests.single().timeout shouldBe 7.seconds
    }
}
