package dev.acme.adbtoolbox.adapters.adb.ddmlib

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.TimeoutException
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Every test wraps assertions in a block body (never `= runBlocking { ... }`) because kotest's
// `shouldBe` returns its receiver, and an expression-bodied `@Test fun` would infer a non-Unit
// return type from the last statement, which JUnit5 silently skips (see JvmProcessExecutorTest).
class DdmlibAdbTransportTest {

    private val serial = DeviceSerial.of("R58N90ABCDE")
    private val shellRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("getprop"))),
    )

    private fun mockDevice(state: IDevice.DeviceState = IDevice.DeviceState.ONLINE): IDevice {
        val device = mockk<IDevice>()
        every { device.serialNumber } returns serial.toString()
        every { device.state } returns state
        return device
    }

    private fun transport(vararg devices: IDevice) = DdmlibAdbTransport(DdmlibDeviceSource { devices.toList() })

    private fun IDevice.respondsWith(vararg chunks: String) {
        every { executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) } answers {
            val receiver = secondArg<IShellOutputReceiver>()
            chunks.forEach { chunk ->
                val bytes = chunk.toByteArray(Charsets.UTF_8)
                receiver.addOutput(bytes, 0, bytes.size)
            }
            receiver.flush()
        }
    }

    @Test
    fun `device host operation is unsupported without invoking a ddmlib shell call`() {
        runBlocking {
            val device = mockDevice()
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Host(listOf("uninstall", "com.acme.shop")),
            )

            val result = transport(device).executeText(request)

            result.outcome.shouldBeInstanceOf<AdbOutcome.Unsupported>()
            verify(exactly = 0) {
                device.executeShellCommand(
                    any<String>(),
                    any<IShellOutputReceiver>(),
                    any<Long>(),
                    any<TimeUnit>(),
                    any<java.io.InputStream>(),
                )
            }
        }
    }

    // --- serial lookup and device state --------------------------------------------------------

    @Test
    fun `a serial unknown to the ddmlib bridge is unsupported so the binary transport can take over`() {
        // The IDE's bridge may not be initialized yet (or tracks a different adb server) while
        // `adb devices` already lists the device. Nothing reached the device, so ADR 0005's
        // Unsupported-only fallback may safely retry on the binary transport.
        runBlocking {
            val other = mockDevice().also { every { it.serialNumber } returns "different-serial" }
            val result = transport(other).executeText(shellRequest)

            val outcome = result.outcome
            outcome.shouldBeInstanceOf<AdbOutcome.Unsupported>()
            outcome.reason shouldContain serial.toString()
            verify(exactly = 0) { other.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) }
        }
    }

    @Test
    fun `offline device is rejected without invoking ddmlib`() {
        runBlocking {
            val device = mockDevice(IDevice.DeviceState.OFFLINE)
            val result = transport(device).executeText(shellRequest)

            val outcome = result.outcome
            outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
            outcome.reason shouldContain "offline"
            verify(exactly = 0) { device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) }
        }
    }

    @Test
    fun `unauthorized device is rejected without invoking ddmlib`() {
        runBlocking {
            val device = mockDevice(IDevice.DeviceState.UNAUTHORIZED)
            val result = transport(device).executeText(shellRequest)

            val outcome = result.outcome
            outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
            outcome.reason shouldContain "unauthorized"
        }
    }

    @Test
    fun `disconnected device is rejected without invoking ddmlib`() {
        runBlocking {
            val device = mockDevice(IDevice.DeviceState.DISCONNECTED)
            val result = transport(device).executeText(shellRequest)

            val outcome = result.outcome
            outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
            outcome.reason shouldContain "disconnected"
        }
    }

    // --- successful shell execution -------------------------------------------------------------

    @Test
    fun `assembles stdout from multiple chunks and never fabricates an exit code`() {
        runBlocking {
            val device = mockDevice()
            device.respondsWith("line-one\n", "line-two\n")

            val result = transport(device).executeText(shellRequest)

            result.outcome shouldBe AdbOutcome.Completed(exitCode = null)
            result.stdout shouldBe "line-one\nline-two\n"
            result.stderr shouldBe ""
        }
    }

    @Test
    fun `a multibyte character split across ddmlib chunks decodes correctly`() {
        runBlocking {
            val euro = "€".toByteArray(Charsets.UTF_8)
            check(euro.size == 3)
            val device = mockDevice()
            every { device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) } answers {
                val receiver = secondArg<IShellOutputReceiver>()
                receiver.addOutput(byteArrayOf(euro[0], euro[1]), 0, 2)
                receiver.addOutput(byteArrayOf(euro[2]), 0, 1)
                receiver.flush()
            }

            val result = transport(device).executeText(shellRequest)

            result.stdout shouldBe "€"
        }
    }

    @Test
    fun `malformed shell output does not crash and is reported as replaced text`() {
        runBlocking {
            val device = mockDevice()
            every { device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) } answers {
                val receiver = secondArg<IShellOutputReceiver>()
                val malformed = byteArrayOf(0xFF.toByte())
                receiver.addOutput(malformed, 0, malformed.size)
                receiver.flush()
            }

            val result = transport(device).executeText(shellRequest)

            result.outcome shouldBe AdbOutcome.Completed(exitCode = null)
            result.stdout shouldBe "�"
        }
    }

    // --- ddmlib exceptions never fabricate success ------------------------------------------------

    @Test
    fun `ddmlib TimeoutException is reported as transport failure`() {
        runBlocking {
            val device = mockDevice()
            every { device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) } throws TimeoutException("no response")

            val result = transport(device).executeText(shellRequest)

            result.outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
        }
    }

    @Test
    fun `ddmlib AdbCommandRejectedException is reported as transport failure`() {
        runBlocking {
            val device = mockDevice()
            every {
                device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>())
            } throws AdbCommandRejectedException("rejected")

            val result = transport(device).executeText(shellRequest)

            result.outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
        }
    }

    @Test
    fun `ddmlib ShellCommandUnresponsiveException is reported as transport failure`() {
        runBlocking {
            val device = mockDevice()
            every {
                device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>())
            } throws ShellCommandUnresponsiveException()

            val result = transport(device).executeText(shellRequest)

            result.outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
        }
    }

    @Test
    fun `ddmlib IOException is reported as transport failure`() {
        runBlocking {
            val device = mockDevice()
            every {
                device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>())
            } throws java.io.IOException("connection reset")

            val result = transport(device).executeText(shellRequest)

            result.outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
        }
    }

    // --- unsupported request shapes ---------------------------------------------------------------

    @Test
    fun `a server-scoped request is unsupported for executeText`() {
        runBlocking {
            val result = transport().executeText(AdbServerRequest(arguments = listOf("devices", "-l")))

            result.outcome.shouldBeInstanceOf<AdbOutcome.Unsupported>()
        }
    }

    @Test
    fun `an exec operation is unsupported for executeText`() {
        runBlocking {
            val device = mockDevice()
            val request = AdbDeviceRequest(serial, AdbOperation.Exec(listOf("screencap", "-p")))

            val result = transport(device).executeText(request)

            result.outcome.shouldBeInstanceOf<AdbOutcome.Unsupported>()
        }
    }

    // --- timeout and cancellation ------------------------------------------------------------------

    @Test
    fun `ddmlib output timeout is disabled with zero rather than a huge value`() {
        // ddmlib's contract is "0 = wait forever". Android Studio's adblib-backed IDevice converts
        // any positive value to nanoseconds; Long.MAX_VALUE / 2 ms overflows there, and its
        // idle-monitoring heartbeat then spins on every Dispatchers.Default thread while the
        // command never completes (reproduced in Android Studio Quail 4, see docs/e2e-testing.md).
        runBlocking {
            val device = mockDevice()
            device.respondsWith("ok")

            transport(device).executeText(shellRequest)

            verify {
                device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), 0L, any<TimeUnit>(), any<java.io.InputStream>())
            }
        }
    }

    @Test
    fun `a request timeout stops the ddmlib call and reports TimedOut`() {
        runBlocking {
            val device = mockDevice()
            every { device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) } answers {
                val receiver = secondArg<IShellOutputReceiver>()
                while (!receiver.isCancelled()) {
                    Thread.sleep(10)
                }
            }
            val request = shellRequest.copy(timeout = 60.milliseconds)

            val result = withTimeout(5.seconds) { transport(device).executeText(request) }

            result.outcome shouldBe AdbOutcome.TimedOut
        }
    }

    /** How the real ddmlib/adblib call ends when its thread is interrupted: blocking socket I/O
     * fails with ClosedByInterruptException (an IOException), not InterruptedException. */
    private fun IDevice.blocksUntilInterrupted(started: CountDownLatch = CountDownLatch(1)) {
        every { executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) } answers {
            started.countDown()
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
                throw java.nio.channels.ClosedByInterruptException()
            }
        }
    }

    @Test
    fun `a request timeout that interrupts blocking ddmlib I-O is reported as TimedOut, not a transport failure`() {
        // Regression (docs/e2e-testing.md): slow-but-healthy commands (e.g. `monkey` on a busy
        // device) surfaced as an "Operation interrupted" error toast instead of a timeout.
        runBlocking {
            val device = mockDevice()
            device.blocksUntilInterrupted()
            val request = shellRequest.copy(timeout = 60.milliseconds)

            val result = withTimeout(5.seconds) { transport(device).executeText(request) }

            result.outcome shouldBe AdbOutcome.TimedOut
        }
    }

    @Test
    fun `cancelling the caller while ddmlib blocks on I-O cancels instead of reporting a transport failure`() {
        // Regression: a superseded refresh (collectLatest) cancelled in-flight `dumpsys package`
        // calls, and each one was logged and shown as an "Operation interrupted" failure.
        runBlocking {
            val device = mockDevice()
            val started = CountDownLatch(1)
            device.blocksUntilInterrupted(started)
            var result: AdbTextResult? = null

            val job = launch(Dispatchers.Default) { result = transport(device).executeText(shellRequest) }
            started.await(2, TimeUnit.SECONDS)
            job.cancel()
            withTimeout(5.seconds) { job.join() }

            job.isCancelled shouldBe true
            result shouldBe null
        }
    }

    @Test
    fun `cancelling the caller tears the ddmlib call down instead of leaving it running`() {
        runBlocking {
            val device = mockDevice()
            val observedCancelled = AtomicBoolean(false)
            val started = CountDownLatch(1)
            every { device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) } answers {
                val receiver = secondArg<IShellOutputReceiver>()
                started.countDown()
                try {
                    while (!receiver.isCancelled()) {
                        Thread.sleep(10)
                    }
                } finally {
                    // Reached whether the loop exits normally (isCancelled() observed true) or
                    // Thread.sleep is interrupted by runInterruptible's cancellation handling —
                    // either way proves the ddmlib call was torn down, not left running.
                    observedCancelled.set(true)
                }
            }

            val job = launch(Dispatchers.Default) { transport(device).executeText(shellRequest) }
            started.await(2, TimeUnit.SECONDS)
            job.cancel()
            withTimeout(5.seconds) { job.join() }

            observedCancelled.get() shouldBe true
        }
    }

    // --- streaming -----------------------------------------------------------------------------

    @Test
    fun `executeStream emits lines as they arrive and a terminal Completed event`() {
        runBlocking {
            val device = mockDevice()
            device.respondsWith("first\n", "second\n")

            val events = withTimeout(5.seconds) { transport(device).executeStream(shellRequest).toList() }

            events shouldBe listOf(
                AdbStreamEvent.Line("first"),
                AdbStreamEvent.Line("second"),
                AdbStreamEvent.Completed(AdbOutcome.Completed(exitCode = null)),
            )
        }
    }

    @Test
    fun `executeStream for a device lookup failure emits only a terminal Completed event`() {
        runBlocking {
            val device = mockDevice(IDevice.DeviceState.OFFLINE)

            val events = withTimeout(5.seconds) { transport(device).executeStream(shellRequest).toList() }

            events.size shouldBe 1
            val terminal = events.single()
            terminal.shouldBeInstanceOf<AdbStreamEvent.Completed>()
            terminal.outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
            verify(exactly = 0) { device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) }
        }
    }

    @Test
    fun `cancelling stream collection detaches the receiver instead of leaking the call`() {
        runBlocking {
            val device = mockDevice()
            val observedCancelled = AtomicBoolean(false)
            val started = CountDownLatch(1)
            every { device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) } answers {
                val receiver = secondArg<IShellOutputReceiver>()
                started.countDown()
                try {
                    while (!receiver.isCancelled()) {
                        val chunk = "tick\n".toByteArray()
                        receiver.addOutput(chunk, 0, chunk.size)
                        Thread.sleep(10)
                    }
                } finally {
                    observedCancelled.set(true)
                }
            }

            val received = CopyOnWriteArrayList<AdbStreamEvent>()
            val job = launch(Dispatchers.Default) {
                transport(device).executeStream(shellRequest).collect { received += it }
            }
            started.await(2, TimeUnit.SECONDS)
            job.cancel()
            withTimeout(5.seconds) { job.join() }

            observedCancelled.get() shouldBe true
        }
    }

    // --- binary output ---------------------------------------------------------------------------

    @Test
    fun `executeBinary streams raw bytes to the sink without decoding`() {
        runBlocking {
            val device = mockDevice()
            val rawBytes = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x89.toByte(), 0x50, 0x4E, 0x47)
            every { device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) } answers {
                val receiver = secondArg<IShellOutputReceiver>()
                receiver.addOutput(rawBytes, 0, rawBytes.size)
                receiver.flush()
            }
            val request = AdbDeviceRequest(serial, AdbOperation.Exec(listOf("screencap", "-p")))
            val written = mutableListOf<Byte>()

            val outcome = transport(device).executeBinary(request) { bytes -> written += bytes.toList() }

            outcome shouldBe AdbOutcome.Completed(exitCode = null)
            written shouldBe rawBytes.toList()
        }
    }

    @Test
    fun `executeBinary rejects an offline device without invoking ddmlib`() {
        runBlocking {
            val device = mockDevice(IDevice.DeviceState.OFFLINE)
            val request = AdbDeviceRequest(serial, AdbOperation.Exec(listOf("screencap", "-p")))

            val outcome = transport(device).executeBinary(request) { }

            outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
            verify(exactly = 0) { device.executeShellCommand(any<String>(), any<IShellOutputReceiver>(), any<Long>(), any<TimeUnit>(), any<java.io.InputStream>()) }
        }
    }
}
