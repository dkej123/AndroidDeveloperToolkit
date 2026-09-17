package dev.acme.adbtoolbox.application.display.fontscale

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetOutcome
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

private class FakeFontScaleResetTransport(
    private val respond: suspend (AdbRequest) -> AdbTextResult,
) : AdbTransport {
    val requests = mutableListOf<AdbRequest>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        requests += request
        return respond(request)
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = error("not used")
    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = error("not used")
}

private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout = stdout, stderr = "")

private fun AdbRequest.isRead(): Boolean =
    ((this as AdbDeviceRequest).operation as AdbOperation.Shell).command.render().contains(" get ")

private val serial = DeviceSerial.of("AAAA111")

class FontScaleOverrideResetUseCaseTest {

    @Test
    fun `featureId matches the font-scale override summary id`() {
        val overrides = FontScaleOverrides()
        val resetUseCase = FontScaleOverrideResetUseCase(FontScaleUseCase(FakeFontScaleResetTransport { ok("1.0") }, overrides), overrides)

        resetUseCase.featureId shouldBe "font-scale"
    }

    @Test
    fun `currentValue reflects the last non-default readback, or null when at the default`() {
        val overrides = FontScaleOverrides()
        val resetUseCase = FontScaleOverrideResetUseCase(FontScaleUseCase(FakeFontScaleResetTransport { ok("") }, overrides), overrides)

        resetUseCase.currentValue(serial) shouldBe null

        overrides.record(serial, 1.3)
        resetUseCase.currentValue(serial) shouldBe "1.3"

        overrides.record(serial, 1.0)
        resetUseCase.currentValue(serial) shouldBe null
    }

    @Test
    fun `reset writes the platform default and reports success from its readback`() = runBlocking {
        val transport = FakeFontScaleResetTransport { request -> if (request.isRead()) ok("1.0") else ok("") }
        val overrides = FontScaleOverrides()
        val resetUseCase = FontScaleOverrideResetUseCase(FontScaleUseCase(transport, overrides), overrides)

        val outcome = resetUseCase.reset(serial)

        outcome shouldBe OverrideResetOutcome.Success
        overrides.overridesFor(serial) shouldBe emptyList()
    }

    @Test
    fun `reapply re-applies the stored value and reports failure from a transport error`() = runBlocking {
        val transport = FakeFontScaleResetTransport { AdbTextResult(AdbOutcome.TimedOut, stdout = "", stderr = "") }
        val overrides = FontScaleOverrides()
        val resetUseCase = FontScaleOverrideResetUseCase(FontScaleUseCase(transport, overrides), overrides)

        val outcome = resetUseCase.reapply(serial, "1.3")

        outcome shouldBe OverrideResetOutcome.Failed("Command timed out")
    }

    @Test
    fun `reapply rejects a non-numeric stored value without issuing a command`() = runBlocking {
        val transport = FakeFontScaleResetTransport { ok("1.0") }
        val overrides = FontScaleOverrides()
        val resetUseCase = FontScaleOverrideResetUseCase(FontScaleUseCase(transport, overrides), overrides)

        val outcome = resetUseCase.reapply(serial, "not-a-number")

        outcome.shouldBeInstanceOf<OverrideResetOutcome.Failed>()
        transport.requests shouldBe emptyList()
    }
}
