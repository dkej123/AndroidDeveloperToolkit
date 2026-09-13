@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import dev.acme.adbtoolbox.domain.apps.UninstallResult
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

private val UNINSTALL_SERIAL = DeviceSerial.of("R58N90ABCDE")
private const val UNINSTALL_PACKAGE = "com.acme.shop"

class UninstallUseCaseTest {

    @Test
    fun `targets the exact serial with the host uninstall command`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "Success", "") })

        UninstallUseCase(transport).uninstall(UNINSTALL_SERIAL, UNINSTALL_PACKAGE)

        transport.textRequests.single() shouldBe AdbDeviceRequest(
            serial = UNINSTALL_SERIAL,
            operation = AdbOperation.Host(listOf("uninstall", UNINSTALL_PACKAGE)),
            timeout = DEFAULT_UNINSTALL_TIMEOUT,
        )
    }

    @Test
    fun `zero exit Success is success`() = runTest {
        result(AdbOutcome.Completed(0), "Success") shouldBe UninstallResult.Success
    }

    @Test
    fun `zero exit failure body is never accepted as success`() = runTest {
        result(AdbOutcome.Completed(0), "Failure [DELETE_FAILED_INTERNAL_ERROR]") shouldBe
            UninstallResult.Failure("Failure [DELETE_FAILED_INTERNAL_ERROR]")
        result(AdbOutcome.Completed(0), "Success", "Failure [DELETE_FAILED_INTERNAL_ERROR]") shouldBe
            UninstallResult.Failure("Success\nFailure [DELETE_FAILED_INTERNAL_ERROR]")
    }

    @Test
    fun `already absent package is distinguished from an uninstall failure`() = runTest {
        result(AdbOutcome.Completed(1), "Failure [not installed for 0]") shouldBe
            UninstallResult.AlreadyMissing("Failure [not installed for 0]")
        result(AdbOutcome.Completed(1), "", "Unknown package: com.acme.shop") shouldBe
            UninstallResult.AlreadyMissing("Unknown package: com.acme.shop")
    }

    @Test
    fun `non-zero and unknown exit status preserve diagnostics`() = runTest {
        result(AdbOutcome.Completed(1), "", "Failure [DELETE_FAILED_INTERNAL_ERROR]") shouldBe
            UninstallResult.Failure("Failure [DELETE_FAILED_INTERNAL_ERROR]")
        result(AdbOutcome.Completed(null), "Success") shouldBe
            UninstallResult.Failure("Command completed with unknown exit status")
        result(AdbOutcome.Completed(null), "Unknown package: com.acme.shop") shouldBe
            UninstallResult.Failure("Command completed with unknown exit status")
    }

    @Test
    fun `timeout cancellation transport and unsupported remain distinct outcomes`() = runTest {
        result(AdbOutcome.TimedOut) shouldBe UninstallResult.TimedOut
        result(AdbOutcome.Cancelled) shouldBe UninstallResult.Cancelled
        result(AdbOutcome.TransportFailure("device offline")) shouldBe UninstallResult.Failure("device offline")
        result(AdbOutcome.Unsupported("host operation unavailable")) shouldBe
            UninstallResult.Failure("host operation unavailable")
    }

    @Test
    fun `duplicate in-flight request is rejected per exact serial and package`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GatedUninstallTransport(gate)
        val useCase = UninstallUseCase(transport)

        val first = async { useCase.uninstall(UNINSTALL_SERIAL, UNINSTALL_PACKAGE) }
        yield()

        useCase.uninstall(UNINSTALL_SERIAL, UNINSTALL_PACKAGE) shouldBe UninstallResult.RejectedDuplicate
        gate.complete(Unit)
        first.await() shouldBe UninstallResult.Success
    }

    private suspend fun result(
        outcome: AdbOutcome,
        stdout: String = "",
        stderr: String = "",
    ): UninstallResult = UninstallUseCase(
        FakeAdbTransport(textScript = { AdbTextResult(outcome, stdout, stderr) }),
    ).uninstall(UNINSTALL_SERIAL, UNINSTALL_PACKAGE)
}

private class GatedUninstallTransport(private val gate: CompletableDeferred<Unit>) : AdbTransport {
    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        gate.await()
        return AdbTextResult(AdbOutcome.Completed(0), "Success", "")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = emptyFlow()
    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}
