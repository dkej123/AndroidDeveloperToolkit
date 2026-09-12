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
import dev.acme.adbtoolbox.domain.apps.AppLifecycleCommands
import dev.acme.adbtoolbox.domain.apps.AppLifecycleResult
import dev.acme.adbtoolbox.domain.apps.AppRestartResult
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
 * Covers task 023's TDD plan for the Force-stop/Launch/Restart command/use-case layer: exact
 * serial/package targeting, exact command values, success, non-zero/unknown status, timeout,
 * cancellation, disconnect, duplicate in-flight rejection, missing launcher, and restart's
 * second-step (launch) failure after a successful force-stop.
 */
class AppLifecycleUseCaseTest {

    @Test
    fun `force-stop targets the exact serial and package with the force-stop shell command`() = runTest {
        val transport = FakeAdbTransport()
        val useCase = AppLifecycleUseCase(transport)

        useCase.forceStop(SERIAL, PACKAGE)

        val request = transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        request.operation shouldBe AdbOperation.Shell(AppLifecycleCommands.forceStop(PACKAGE))
    }

    @Test
    fun `launch targets the exact serial and package with the monkey launch shell command`() = runTest {
        val transport = FakeAdbTransport()
        val useCase = AppLifecycleUseCase(transport)

        useCase.launch(SERIAL, PACKAGE)

        val request = transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        request.operation shouldBe AdbOperation.Shell(AppLifecycleCommands.launch(PACKAGE))
    }

    @Test
    fun `restart issues force-stop then launch, in that order, for the exact serial and package`() = runTest {
        val transport = FakeAdbTransport()
        val useCase = AppLifecycleUseCase(transport)

        useCase.restart(SERIAL, PACKAGE)

        val requests = transport.textRequests.map { it as AdbDeviceRequest }
        requests.map { it.operation } shouldBe listOf(
            AdbOperation.Shell(AppLifecycleCommands.forceStop(PACKAGE)),
            AdbOperation.Shell(AppLifecycleCommands.launch(PACKAGE)),
        )
        requests.forEach { it.serial shouldBe SERIAL }
    }

    @Test
    fun `a zero exit code is success for force-stop`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "", "") })
        val useCase = AppLifecycleUseCase(transport)

        useCase.forceStop(SERIAL, PACKAGE) shouldBe AppLifecycleResult.Success
    }

    @Test
    fun `a null (unknown) exit code is also success for launch, never coerced to a failure`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(null), "", "") })
        val useCase = AppLifecycleUseCase(transport)

        useCase.launch(SERIAL, PACKAGE) shouldBe AppLifecycleResult.Success
    }

    @Test
    fun `a non-zero exit code is a failure carrying the exit code`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(1), "", "denied") })
        val useCase = AppLifecycleUseCase(transport)

        val result = useCase.forceStop(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<AppLifecycleResult.Failure>()
        (result as AppLifecycleResult.Failure).reason shouldBe "Command exited with code 1"
    }

    @Test
    fun `a timeout is a failure`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.TimedOut, "", "") })
        val useCase = AppLifecycleUseCase(transport)

        val result = useCase.launch(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<AppLifecycleResult.Failure>()
        (result as AppLifecycleResult.Failure).reason shouldBe "Timed out"
    }

    @Test
    fun `cancellation is preserved as its own distinct failure reason`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Cancelled, "", "") })
        val useCase = AppLifecycleUseCase(transport)

        val result = useCase.forceStop(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<AppLifecycleResult.Failure>()
        (result as AppLifecycleResult.Failure).reason shouldBe "Cancelled"
    }

    @Test
    fun `a disconnected device surfaces the transport's own failure reason`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "") },
        )
        val useCase = AppLifecycleUseCase(transport)

        val result = useCase.launch(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<AppLifecycleResult.Failure>()
        (result as AppLifecycleResult.Failure).reason shouldBe "device offline"
    }

    @Test
    fun `an unknown exit status other than completed-with-code is never silently treated as success`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Unsupported("no equivalent"), "", "") },
        )
        val useCase = AppLifecycleUseCase(transport)

        val result = useCase.forceStop(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<AppLifecycleResult.Failure>()
        (result as AppLifecycleResult.Failure).reason shouldBe "no equivalent"
    }

    @Test
    fun `a missing launcher activity is reported as its own distinct failure reason`() = runTest {
        val transport = FakeAdbTransport(
            textScript = {
                AdbTextResult(AdbOutcome.Completed(1), "", "No activities found to run, monkey aborted.")
            },
        )
        val useCase = AppLifecycleUseCase(transport)

        val result = useCase.launch(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<AppLifecycleResult.Failure>()
        (result as AppLifecycleResult.Failure).reason shouldBe "No launcher activity found for $PACKAGE"
    }

    @Test
    fun `restart never reports success when launch fails after a successful force-stop`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request ->
                val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command
                if (command == AppLifecycleCommands.forceStop(PACKAGE)) {
                    AdbTextResult(AdbOutcome.Completed(0), "", "")
                } else {
                    AdbTextResult(AdbOutcome.Completed(1), "", "No activities found to run, monkey aborted.")
                }
            },
        )
        val useCase = AppLifecycleUseCase(transport)

        val result = useCase.restart(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<AppRestartResult.LaunchFailedAfterForceStop>()
        (result as AppRestartResult.LaunchFailedAfterForceStop).reason shouldBe "No launcher activity found for $PACKAGE"
        // Exactly one launch attempt followed the one successful force-stop.
        transport.textRequests.size shouldBe 2
    }

    @Test
    fun `restart never attempts launch when force-stop itself fails`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "") },
        )
        val useCase = AppLifecycleUseCase(transport)

        val result = useCase.restart(SERIAL, PACKAGE)

        result.shouldBeInstanceOf<AppRestartResult.ForceStopFailed>()
        (result as AppRestartResult.ForceStopFailed).reason shouldBe "device offline"
        transport.textRequests.size shouldBe 1
    }

    @Test
    fun `restart reports success only when both force-stop and launch succeed`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "", "") })
        val useCase = AppLifecycleUseCase(transport)

        useCase.restart(SERIAL, PACKAGE) shouldBe AppRestartResult.Success
    }

    @Test
    fun `a second force-stop request for the same serial and package while one is in flight is rejected`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GatedFakeAdbTransport(gate)
        val useCase = AppLifecycleUseCase(transport)

        val first = async { useCase.forceStop(SERIAL, PACKAGE) }
        yield()
        val second = useCase.forceStop(SERIAL, PACKAGE)

        second shouldBe AppLifecycleResult.RejectedDuplicate
        gate.complete(Unit)
        first.await() shouldBe AppLifecycleResult.Success
    }

    @Test
    fun `a duplicate request is scoped to the exact package and serial, never blocking a different one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GatedFakeAdbTransport(gate)
        val useCase = AppLifecycleUseCase(transport)

        val first = async { useCase.forceStop(SERIAL, PACKAGE) }
        yield()
        val differentPackage = async { useCase.forceStop(SERIAL, "com.acme.other") }
        val differentSerial = async { useCase.forceStop(DeviceSerial.of("OTHERSERIAL"), PACKAGE) }
        yield()

        gate.complete(Unit)
        first.await() shouldBe AppLifecycleResult.Success
        differentPackage.await() shouldBe AppLifecycleResult.Success
        differentSerial.await() shouldBe AppLifecycleResult.Success
    }

    @Test
    fun `a restart request for the same serial and package while one is in flight is rejected`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GatedFakeAdbTransport(gate)
        val useCase = AppLifecycleUseCase(transport)

        val first = async { useCase.restart(SERIAL, PACKAGE) }
        yield()
        val second = useCase.restart(SERIAL, PACKAGE)

        second shouldBe AppRestartResult.RejectedDuplicate
        gate.complete(Unit)
        first.await() shouldBe AppRestartResult.Success
    }

    @Test
    fun `the guard releases once an action completes, allowing a subsequent request for the same key`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "", "") })
        val useCase = AppLifecycleUseCase(transport)

        useCase.forceStop(SERIAL, PACKAGE) shouldBe AppLifecycleResult.Success
        useCase.forceStop(SERIAL, PACKAGE) shouldBe AppLifecycleResult.Success
    }
}

/** An [AdbTransport] whose [executeText] suspends on [gate] until released, one call at a time. */
private class GatedFakeAdbTransport(private val gate: CompletableDeferred<Unit>) : AdbTransport {
    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        gate.await()
        return AdbTextResult(AdbOutcome.Completed(0), "", "")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = emptyFlow()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}
