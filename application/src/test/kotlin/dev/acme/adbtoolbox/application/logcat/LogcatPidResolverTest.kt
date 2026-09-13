package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.logcat.LogcatPidCommand
import dev.acme.adbtoolbox.domain.logcat.LogcatPidResolution
import dev.acme.adbtoolbox.domain.logcat.ProcessId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val SERIAL = DeviceSerial.of("R58N90ABCDE")
private const val PACKAGE = "com.acme.shop"

/**
 * Covers task 035's TDD plan step 2 at the use-case layer: exact serial/package targeting through
 * the `pidof` shell command, and every [LogcatPidResolution] outcome including transport failures.
 */
class LogcatPidResolverTest {

    @Test
    fun `resolve targets the exact serial and package with the pidof shell command`() = runTest {
        val transport = FakeAdbTransport()
        val resolver = LogcatPidResolver(transport)

        resolver.resolve(SERIAL, PACKAGE)

        val request = transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe SERIAL
        request.operation shouldBe AdbOperation.Shell(LogcatPidCommand.pidOf(PACKAGE))
    }

    @Test
    fun `a single pid in stdout resolves`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "4321\n", "") })
        val resolver = LogcatPidResolver(transport)

        resolver.resolve(SERIAL, PACKAGE) shouldBe LogcatPidResolution.Resolved(setOf(ProcessId(4321)))
    }

    @Test
    fun `multiple pids in stdout all resolve`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "111 222\n", "") })
        val resolver = LogcatPidResolver(transport)

        resolver.resolve(SERIAL, PACKAGE) shouldBe LogcatPidResolution.Resolved(setOf(ProcessId(111), ProcessId(222)))
    }

    @Test
    fun `empty stdout on a non-zero exit (the common not-running case) is no process, not a failure`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(1), "", "") })
        val resolver = LogcatPidResolver(transport)

        resolver.resolve(SERIAL, PACKAGE) shouldBe LogcatPidResolution.NoProcess
    }

    @Test
    fun `non-numeric stdout is malformed`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(127), "pidof: not found", "") },
        )
        val resolver = LogcatPidResolver(transport)

        resolver.resolve(SERIAL, PACKAGE) shouldBe LogcatPidResolution.Malformed("pidof: not found")
    }

    @Test
    fun `a timeout is a failure`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.TimedOut, "", "") })
        val resolver = LogcatPidResolver(transport)

        resolver.resolve(SERIAL, PACKAGE) shouldBe LogcatPidResolution.Failed("Timed out")
    }

    @Test
    fun `cancellation is preserved as its own distinct failure reason`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Cancelled, "", "") })
        val resolver = LogcatPidResolver(transport)

        resolver.resolve(SERIAL, PACKAGE) shouldBe LogcatPidResolution.Failed("Cancelled")
    }

    @Test
    fun `a disconnected device surfaces the transport's own failure reason`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "") },
        )
        val resolver = LogcatPidResolver(transport)

        resolver.resolve(SERIAL, PACKAGE) shouldBe LogcatPidResolution.Failed("device offline")
    }

    @Test
    fun `an unsupported transport call is a failure`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Unsupported("no equivalent"), "", "") },
        )
        val resolver = LogcatPidResolver(transport)

        resolver.resolve(SERIAL, PACKAGE) shouldBe LogcatPidResolution.Failed("no equivalent")
    }

    @Test
    fun `re-resolving after an app restart reflects the new pid without any other state`() = runTest {
        var stdout = "1000"
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), stdout, "") })
        val resolver = LogcatPidResolver(transport)

        resolver.resolve(SERIAL, PACKAGE) shouldBe LogcatPidResolution.Resolved(setOf(ProcessId(1000)))

        stdout = "2000"
        resolver.resolve(SERIAL, PACKAGE) shouldBe LogcatPidResolution.Resolved(setOf(ProcessId(2000)))
    }
}
