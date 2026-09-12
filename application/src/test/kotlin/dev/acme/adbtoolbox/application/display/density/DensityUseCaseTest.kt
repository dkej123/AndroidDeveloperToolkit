package dev.acme.adbtoolbox.application.display.density

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.display.density.DensityReading
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val SERIAL_A = DeviceSerial.of("emulator-5554")
private val SERIAL_B = DeviceSerial.of("192.168.1.42:5555")

class DensityUseCaseTest {

    @Test
    fun `read builds an exact-serial-scoped request and returns the parsed reading`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\n", "") },
        )
        val useCase = DensityUseCase(transport, DensityOverrideTracker())

        val result = useCase.read(SERIAL_A)

        result shouldBe DensityResult.Success(SERIAL_A, DensityReading(420, null))
        val request = transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL_A
        (request.operation as AdbOperation.Shell).command.render() shouldBe "wm density"
    }

    @Test
    fun `applyPreset resolves the percentage against the physical dpi and reports the readback value`() = runTest {
        var applied: Int? = null
        val transport = FakeAdbTransport(
            textScript = { request ->
                val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
                when {
                    command == "wm density" && applied == null ->
                        AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\n", "")
                    command.startsWith("wm density ") && command != "wm density reset" -> {
                        applied = command.removePrefix("wm density ").toInt()
                        AdbTextResult(AdbOutcome.Completed(0), "", "")
                    }
                    else -> AdbTextResult(
                        AdbOutcome.Completed(0),
                        "Physical density: 420\nOverride density: $applied\n",
                        "",
                    )
                }
            },
        )
        val useCase = DensityUseCase(transport, DensityOverrideTracker())

        // 420 * 125% = 525
        val result = useCase.applyPreset(SERIAL_A, 125)

        result shouldBe DensityResult.Success(SERIAL_A, DensityReading(420, 525))
        applied shouldBe 525
    }

    @Test
    fun `applyCustom rejects an unsafe dpi before issuing any set command`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\n", "") },
        )
        val useCase = DensityUseCase(transport, DensityOverrideTracker())

        val result = useCase.applyCustom(SERIAL_A, 1)

        result.shouldBeInstanceOf<DensityResult.Invalid>()
        // Only the pre-flight read happened -- no "wm density <dpi>" set command was ever sent.
        transport.textRequests.map { (it as AdbDeviceRequest).operation }
            .filterIsInstance<AdbOperation.Shell>()
            .none { it.command.render().matches(Regex("wm density \\d+")) } shouldBe true
    }

    @Test
    fun `applyCustom reports the readback value even when the device applies a different dpi than requested`() =
        runTest {
            var readCount = 0
            val transport = FakeAdbTransport(
                textScript = { request ->
                    val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
                    when {
                        command == "wm density 480" -> AdbTextResult(AdbOutcome.Completed(0), "", "")
                        command == "wm density" -> {
                            readCount++
                            if (readCount == 1) {
                                AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\n", "")
                            } else {
                                // Device clamped the requested 480 down to 460.
                                AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\nOverride density: 460\n", "")
                            }
                        }
                        else -> error("unexpected command: $command")
                    }
                },
            )
            val useCase = DensityUseCase(transport, DensityOverrideTracker())

            val result = useCase.applyCustom(SERIAL_A, 480)

            result shouldBe DensityResult.Success(SERIAL_A, DensityReading(420, 460))
        }

    @Test
    fun `reset issues wm density reset then reports the physical-only readback`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request ->
                val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
                if (command == "wm density reset") {
                    AdbTextResult(AdbOutcome.Completed(0), "", "")
                } else {
                    AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\n", "")
                }
            },
        )
        val useCase = DensityUseCase(transport, DensityOverrideTracker())

        val result = useCase.reset(SERIAL_A)

        result shouldBe DensityResult.Success(SERIAL_A, DensityReading(420, null))
    }

    @Test
    fun `a transport failure on the set command is reported without a spurious readback`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request ->
                val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
                when {
                    command == "wm density" -> AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\n", "")
                    else -> AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "")
                }
            },
        )
        val useCase = DensityUseCase(transport, DensityOverrideTracker())

        val result = useCase.applyPreset(SERIAL_A, 100)

        result shouldBe DensityResult.TransportError(SERIAL_A, AdbOutcome.TransportFailure("device offline"))
    }

    @Test
    fun `a timeout on read is reported as a typed transport error`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.TimedOut, "", "") })
        val useCase = DensityUseCase(transport, DensityOverrideTracker())

        useCase.read(SERIAL_A) shouldBe DensityResult.TransportError(SERIAL_A, AdbOutcome.TimedOut)
    }

    @Test
    fun `cancelling an in-flight call releases the serialization lock for the next call`() = runTest(
        timeout = kotlin.time.Duration.parse("5s"),
    ) {
        val started = CompletableDeferred<Unit>()
        var hangOnce = true
        val transport = object : AdbTransport {
            override suspend fun executeText(request: AdbRequest): AdbTextResult {
                if (hangOnce) {
                    hangOnce = false
                    started.complete(Unit)
                    awaitCancellation()
                }
                return AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\n", "")
            }

            override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = emptyFlow()

            override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome =
                AdbOutcome.Completed(0)
        }
        val useCase = DensityUseCase(transport, DensityOverrideTracker())

        val job = launch { useCase.read(SERIAL_A) }
        started.await()
        job.cancelAndJoin()

        val result = useCase.read(SERIAL_A)

        result shouldBe DensityResult.Success(SERIAL_A, DensityReading(420, null))
    }

    @Test
    fun `results for two different serials never cross-contaminate the override tracker`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request ->
                val serial = (request as AdbDeviceRequest).serial
                when (serial) {
                    SERIAL_A -> AdbTextResult(AdbOutcome.Completed(0), "Physical density: 420\nOverride density: 480\n", "")
                    SERIAL_B -> AdbTextResult(AdbOutcome.Completed(0), "Physical density: 320\n", "")
                    else -> error("unexpected serial")
                }
            },
        )
        val tracker = DensityOverrideTracker()
        val useCase = DensityUseCase(transport, tracker)

        useCase.read(SERIAL_A)
        useCase.read(SERIAL_B)

        tracker.overridesFor(SERIAL_A) shouldBe listOf(
            dev.acme.adbtoolbox.domain.devicecontext.OverrideSummary("density", "Density: 114% (480 dpi)"),
        )
        tracker.overridesFor(SERIAL_B) shouldBe emptyList()
    }
}
