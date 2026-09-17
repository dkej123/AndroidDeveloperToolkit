package dev.acme.adbtoolbox.application.display.density

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetOutcome
import dev.acme.adbtoolbox.domain.display.density.DensityReading
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

private class FakeDensityResetTransport(
    private val respond: suspend (AdbDeviceRequest) -> AdbTextResult,
) : AdbTransport {
    val requests = mutableListOf<AdbDeviceRequest>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        val deviceRequest = request as AdbDeviceRequest
        requests += deviceRequest
        return respond(deviceRequest)
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = error("not used")
    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = error("not used")
}

private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout = stdout, stderr = "")

private val serial = DeviceSerial.of("AAAA111")

class DensityOverrideResetUseCaseTest {

    @Test
    fun `featureId matches the density override summary id`() {
        val tracker = DensityOverrideTracker()
        val useCase = DensityUseCase(FakeDensityResetTransport { ok("Physical density: 420") }, tracker)
        val resetUseCase = DensityOverrideResetUseCase(useCase, tracker)

        resetUseCase.featureId shouldBe "density"
    }

    @Test
    fun `currentValue reflects the tracker's last override dpi, or null when there is none`() {
        val tracker = DensityOverrideTracker()
        val useCase = DensityUseCase(FakeDensityResetTransport { ok("") }, tracker)
        val resetUseCase = DensityOverrideResetUseCase(useCase, tracker)

        resetUseCase.currentValue(serial) shouldBe null

        tracker.record(serial, DensityReading(420, 525))

        resetUseCase.currentValue(serial) shouldBe "525"
    }

    @Test
    fun `reset delegates to the density use case and reports success from its readback`() = runBlocking {
        val transport = FakeDensityResetTransport { request ->
            if ((request.operation as AdbOperation.Shell).command.render().contains("reset")) {
                ok("")
            } else {
                ok("Physical density: 420")
            }
        }
        val tracker = DensityOverrideTracker()
        val useCase = DensityUseCase(transport, tracker)
        val resetUseCase = DensityOverrideResetUseCase(useCase, tracker)

        val outcome = resetUseCase.reset(serial)

        outcome shouldBe OverrideResetOutcome.Success
        tracker.overrideDpiFor(serial) shouldBe null
    }

    @Test
    fun `reapply re-applies the stored dpi and reports failure from a transport error`() = runBlocking {
        val transport = FakeDensityResetTransport { AdbTextResult(AdbOutcome.TimedOut, stdout = "", stderr = "") }
        val tracker = DensityOverrideTracker()
        val useCase = DensityUseCase(transport, tracker)
        val resetUseCase = DensityOverrideResetUseCase(useCase, tracker)

        val outcome = resetUseCase.reapply(serial, "525")

        outcome shouldBe OverrideResetOutcome.Failed("Command timed out")
    }

    @Test
    fun `reapply rejects a non-numeric stored value without issuing a command`() = runBlocking {
        val transport = FakeDensityResetTransport { ok("Physical density: 420") }
        val tracker = DensityOverrideTracker()
        val useCase = DensityUseCase(transport, tracker)
        val resetUseCase = DensityOverrideResetUseCase(useCase, tracker)

        val outcome = resetUseCase.reapply(serial, "not-a-number")

        outcome.shouldBeInstanceOf<OverrideResetOutcome.Failed>()
        transport.requests shouldBe emptyList()
    }
}
