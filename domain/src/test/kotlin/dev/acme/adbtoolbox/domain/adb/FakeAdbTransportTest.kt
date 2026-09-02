package dev.acme.adbtoolbox.domain.adb

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val ANY_DEVICE_REQUEST = AdbDeviceRequest(
    serial = DeviceSerial.of("emulator-5554"),
    operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("getprop"))),
)

class FakeAdbTransportTest {

    @Test
    fun `executeText returns the scripted bounded result and records the request`() = runTest {
        val transport = FakeAdbTransport(
            textScript = {
                AdbTextResult(
                    outcome = AdbOutcome.Completed(exitCode = 0),
                    stdout = "sdk_version=34\n",
                    stderr = "",
                )
            },
        )

        val result = transport.executeText(ANY_DEVICE_REQUEST)

        result.stdout shouldBe "sdk_version=34\n"
        result.outcome shouldBe AdbOutcome.Completed(exitCode = 0)
        transport.textRequests shouldBe listOf(ANY_DEVICE_REQUEST)
    }

    @Test
    fun `executeText normalizes a transport failure such as an offline device`() = runTest {
        val transport = FakeAdbTransport(
            textScript = {
                AdbTextResult(
                    outcome = AdbOutcome.TransportFailure("device offline"),
                    stdout = "",
                    stderr = "",
                )
            },
        )

        val result = transport.executeText(ANY_DEVICE_REQUEST)

        result.outcome shouldBe AdbOutcome.TransportFailure("device offline")
    }

    @Test
    fun `executeText normalizes a timeout without a real delay`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(outcome = AdbOutcome.TimedOut, stdout = "", stderr = "") },
        )

        transport.executeText(ANY_DEVICE_REQUEST).outcome shouldBe AdbOutcome.TimedOut
    }

    @Test
    fun `executeText normalizes cancellation distinctly from a timeout`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(outcome = AdbOutcome.Cancelled, stdout = "", stderr = "") },
        )

        transport.executeText(ANY_DEVICE_REQUEST).outcome shouldBe AdbOutcome.Cancelled
    }

    @Test
    fun `executeText normalizes an unsupported operation as a transport limitation`() = runTest {
        val transport = FakeAdbTransport(
            textScript = {
                AdbTextResult(
                    outcome = AdbOutcome.Unsupported("ddmlib has no pairing equivalent"),
                    stdout = "",
                    stderr = "",
                )
            },
        )

        transport.executeText(ANY_DEVICE_REQUEST).outcome shouldBe
            AdbOutcome.Unsupported("ddmlib has no pairing equivalent")
    }

    @Test
    fun `executeStream replays scripted line events then a terminal Completed event`() = runTest {
        val transport = FakeAdbTransport(
            streamScript = {
                listOf(
                    AdbStreamEvent.Line("--- beginning of main"),
                    AdbStreamEvent.Line("D/Foo: bar"),
                    AdbStreamEvent.Completed(AdbOutcome.Cancelled),
                )
            },
        )

        val events = transport.executeStream(ANY_DEVICE_REQUEST).toList()

        events shouldBe listOf(
            AdbStreamEvent.Line("--- beginning of main"),
            AdbStreamEvent.Line("D/Foo: bar"),
            AdbStreamEvent.Completed(AdbOutcome.Cancelled),
        )
        transport.streamRequests shouldBe listOf(ANY_DEVICE_REQUEST)
    }

    @Test
    fun `executeBinary writes scripted chunks to the sink and returns the scripted outcome`() = runTest {
        val transport = FakeAdbTransport(
            binaryScript = {
                AdbBinaryScript(
                    chunks = listOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), byteArrayOf(1, 2, 3)),
                    outcome = AdbOutcome.Completed(exitCode = 0),
                )
            },
        )
        val written = mutableListOf<ByteArray>()

        val outcome = transport.executeBinary(ANY_DEVICE_REQUEST) { bytes -> written += bytes }

        outcome shouldBe AdbOutcome.Completed(exitCode = 0)
        written shouldBe listOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), byteArrayOf(1, 2, 3))
        transport.binaryRequests shouldBe listOf(ANY_DEVICE_REQUEST)
    }

    @Test
    fun `executeBinary never writes to the sink when the transport failed before any bytes arrived`() = runTest {
        val transport = FakeAdbTransport(
            binaryScript = {
                AdbBinaryScript(chunks = emptyList(), outcome = AdbOutcome.TransportFailure("no device"))
            },
        )
        val written = mutableListOf<ByteArray>()

        val outcome = transport.executeBinary(ANY_DEVICE_REQUEST) { bytes -> written += bytes }

        outcome shouldBe AdbOutcome.TransportFailure("no device")
        written shouldBe emptyList()
    }
}
