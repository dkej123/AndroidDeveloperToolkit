package dev.acme.adbtoolbox.application.deviceactions

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.deviceactions.DeviceActionCommands
import dev.acme.adbtoolbox.domain.deviceactions.DeviceActionResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val SERIAL = DeviceSerial.of("R58N90ABCDE")

/**
 * Covers task 016's TDD plan for the Reboot/Wake command/use-case pair: exact serial, success,
 * non-zero/unknown status, timeout, cancellation, and disconnect (transport failure) — duplicate
 * in-flight prevention is a presenter-level concern, covered by [DeviceActionsViewModelTest].
 */
class DeviceActionsUseCaseTest {

    @Test
    fun `reboot targets the exact serial with the reboot shell command`() = runTest {
        val transport = FakeAdbTransport()
        val useCase = DeviceActionsUseCase(transport)

        useCase.reboot(SERIAL)

        val request = transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        request.operation shouldBe AdbOperation.Shell(DeviceActionCommands.reboot())
    }

    @Test
    fun `wake targets the exact serial with the wake shell command`() = runTest {
        val transport = FakeAdbTransport()
        val useCase = DeviceActionsUseCase(transport)

        useCase.wake(SERIAL)

        val request = transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        request.operation shouldBe AdbOperation.Shell(DeviceActionCommands.wake())
    }

    @Test
    fun `a zero exit code is success`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "", "") })
        val useCase = DeviceActionsUseCase(transport)

        useCase.reboot(SERIAL) shouldBe DeviceActionResult.Success
    }

    @Test
    fun `a null (unknown) exit code is also success, never coerced to a failure`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(null), "", "") })
        val useCase = DeviceActionsUseCase(transport)

        useCase.wake(SERIAL) shouldBe DeviceActionResult.Success
    }

    @Test
    fun `a non-zero exit code is a failure carrying the exit code`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(1), "", "denied") })
        val useCase = DeviceActionsUseCase(transport)

        val result = useCase.reboot(SERIAL)

        result.shouldBeInstanceOf<DeviceActionResult.Failure>()
        (result as DeviceActionResult.Failure).reason shouldBe "Command exited with code 1"
    }

    @Test
    fun `a timeout is a failure`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.TimedOut, "", "") })
        val useCase = DeviceActionsUseCase(transport)

        val result = useCase.wake(SERIAL)

        result.shouldBeInstanceOf<DeviceActionResult.Failure>()
        (result as DeviceActionResult.Failure).reason shouldBe "Timed out"
    }

    @Test
    fun `cancellation is preserved as its own distinct failure reason, not collapsed into a timeout`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Cancelled, "", "") })
        val useCase = DeviceActionsUseCase(transport)

        val result = useCase.reboot(SERIAL)

        result.shouldBeInstanceOf<DeviceActionResult.Failure>()
        (result as DeviceActionResult.Failure).reason shouldBe "Cancelled"
    }

    @Test
    fun `a disconnected device surfaces the transport's own failure reason`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "") },
        )
        val useCase = DeviceActionsUseCase(transport)

        val result = useCase.wake(SERIAL)

        result.shouldBeInstanceOf<DeviceActionResult.Failure>()
        (result as DeviceActionResult.Failure).reason shouldBe "device offline"
    }

    @Test
    fun `an unsupported transport call surfaces its own reason`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Unsupported("no equivalent"), "", "") },
        )
        val useCase = DeviceActionsUseCase(transport)

        val result = useCase.reboot(SERIAL)

        result.shouldBeInstanceOf<DeviceActionResult.Failure>()
        (result as DeviceActionResult.Failure).reason shouldBe "no equivalent"
    }
}
