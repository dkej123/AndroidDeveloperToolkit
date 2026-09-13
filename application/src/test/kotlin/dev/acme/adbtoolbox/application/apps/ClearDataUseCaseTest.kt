package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.apps.ClearDataCommands
import dev.acme.adbtoolbox.domain.apps.ClearDataResult
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

private val SERIAL = DeviceSerial.of("R58N90ABCDE")
private const val PACKAGE = "com.acme.shop"

/**
 * Covers task 024's TDD plan for the Clear-data command/use-case layer: exact serial/package
 * targeting, exact command value, success (including a truthful non-"Success" body on an
 * otherwise-zero exit — `pm clear`'s own documented response shape, per
 * `.claude/skills/adb-development/references/adbhelper.md`'s pointer to `ClearAppDataReceiver`),
 * failure, timeout, cancellation, disconnect, and duplicate in-flight rejection scoped to the exact
 * (serial, package) pair.
 */
class ClearDataUseCaseTest {

    @Test
    fun `clearData targets the exact serial and package with the pm clear shell command`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "Success", "") })
        val useCase = ClearDataUseCase(transport)

        useCase.clearData(SERIAL, PACKAGE)

        val request = transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        request.operation shouldBe AdbOperation.Shell(ClearDataCommands.clear(PACKAGE))
    }

    @Test
    fun `a zero exit code with a Success body is success`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "Success", "") })
        val useCase = ClearDataUseCase(transport)

        useCase.clearData(SERIAL, PACKAGE) shouldBe ClearDataResult.Success
    }

    @Test
    fun `a null exit code remains an explicit unknown-status failure even with a Success body`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(null), "Success", "") })
        val useCase = ClearDataUseCase(transport)

        useCase.clearData(SERIAL, PACKAGE) shouldBe
            ClearDataResult.Failure("Command completed with unknown exit status")
    }

    @Test
    fun `a zero exit code whose body reports Failed is truthfully a failure, never coerced to success`() = runTest {
        // Some Android versions exit 0 from `pm clear` even when the command itself failed — the
        // shell's own text is the only truthful signal in that case (adbhelper.md's pointer to
        // ClearAppDataReceiver: "pm clear behavior and response shape").
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "Failed", "") })
        val useCase = ClearDataUseCase(transport)

        val result = useCase.clearData(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<ClearDataResult.Failure>()
        (result as ClearDataResult.Failure).reason shouldBe "Failed"
    }

    @Test
    fun `a non-zero exit code is a failure carrying the exit code when no output is present`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(1), "", "") })
        val useCase = ClearDataUseCase(transport)

        val result = useCase.clearData(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<ClearDataResult.Failure>()
        (result as ClearDataResult.Failure).reason shouldBe "Command exited with code 1"
    }

    @Test
    fun `a non-zero exit code preserves the command's own error text when present`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(1), "", "Unknown package: com.acme.shop") },
        )
        val useCase = ClearDataUseCase(transport)

        val result = useCase.clearData(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<ClearDataResult.Failure>()
        (result as ClearDataResult.Failure).reason shouldBe "Unknown package: com.acme.shop"
    }

    @Test
    fun `a timeout is a failure`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.TimedOut, "", "") })
        val useCase = ClearDataUseCase(transport)

        val result = useCase.clearData(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<ClearDataResult.Failure>()
        (result as ClearDataResult.Failure).reason shouldBe "Timed out"
    }

    @Test
    fun `cancellation is preserved as its own distinct failure reason`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Cancelled, "", "") })
        val useCase = ClearDataUseCase(transport)

        val result = useCase.clearData(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<ClearDataResult.Failure>()
        (result as ClearDataResult.Failure).reason shouldBe "Cancelled"
    }

    @Test
    fun `a disconnected device surfaces the transport's own failure reason`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "") },
        )
        val useCase = ClearDataUseCase(transport)

        val result = useCase.clearData(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<ClearDataResult.Failure>()
        (result as ClearDataResult.Failure).reason shouldBe "device offline"
    }

    @Test
    fun `an unsupported transport call is never silently treated as success`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Unsupported("no equivalent"), "", "") },
        )
        val useCase = ClearDataUseCase(transport)

        val result = useCase.clearData(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<ClearDataResult.Failure>()
        (result as ClearDataResult.Failure).reason shouldBe "no equivalent"
    }

    @Test
    fun `a second clear-data request for the same serial and package while one is in flight is rejected`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GatedClearDataAdbTransport(gate)
        val useCase = ClearDataUseCase(transport)

        val first = async { useCase.clearData(SERIAL, PACKAGE) }
        yield()
        val second = useCase.clearData(SERIAL, PACKAGE)

        second shouldBe ClearDataResult.RejectedDuplicate
        gate.complete(Unit)
        first.await() shouldBe ClearDataResult.Success
    }

    @Test
    fun `a duplicate request is scoped to the exact package and serial, never blocking a different one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GatedClearDataAdbTransport(gate)
        val useCase = ClearDataUseCase(transport)

        val first = async { useCase.clearData(SERIAL, PACKAGE) }
        yield()
        val differentPackage = async { useCase.clearData(SERIAL, "com.acme.other") }
        val differentSerial = async { useCase.clearData(DeviceSerial.of("OTHERSERIAL"), PACKAGE) }
        yield()

        gate.complete(Unit)
        first.await() shouldBe ClearDataResult.Success
        differentPackage.await() shouldBe ClearDataResult.Success
        differentSerial.await() shouldBe ClearDataResult.Success
    }

    @Test
    fun `the guard releases once a call completes, allowing a subsequent request for the same key`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "Success", "") })
        val useCase = ClearDataUseCase(transport)

        useCase.clearData(SERIAL, PACKAGE) shouldBe ClearDataResult.Success
        useCase.clearData(SERIAL, PACKAGE) shouldBe ClearDataResult.Success
    }
}

/** An [AdbTransport] whose [executeText] suspends on [gate] until released, one call at a time. */
private class GatedClearDataAdbTransport(private val gate: CompletableDeferred<Unit>) : AdbTransport {
    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        gate.await()
        return AdbTextResult(AdbOutcome.Completed(0), "Success", "")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = emptyFlow()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}
