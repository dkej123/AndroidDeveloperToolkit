package io.github.dkej123.devicecockpit.application.diagnostics

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbServerRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbStreamEvent
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.diagnostics.DiagCategory
import io.github.dkej123.devicecockpit.domain.diagnostics.DiagLevel
import io.github.dkej123.devicecockpit.domain.diagnostics.RecordingDiagnosticsLog
import io.github.dkej123.devicecockpit.domain.process.ByteSink
import io.github.dkej123.devicecockpit.domain.time.FakeMonotonicClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LoggingAdbTransportTest {

    private val clock = FakeMonotonicClock()
    private val log = RecordingDiagnosticsLog()
    private val serial = DeviceSerial.of("R58N90ABCDE")

    @Test
    fun `text results are returned unchanged and logged with the transport name and request`() = runTest {
        val result = AdbTextResult(AdbOutcome.Completed(0), "List of devices attached\n", "")
        val transport = LoggingAdbTransport(FakeAdbTransport(textScript = { result }), "binary", log, clock)

        transport.executeText(AdbServerRequest(listOf("devices", "-l"))) shouldBe result

        val entry = log.inCategory(DiagCategory.ADB).last()
        entry.fields["transport"] shouldBe "binary"
        entry.fields["request"] shouldBe "server devices -l"
        entry.fields["outcome"] shouldBe "Completed(exitCode=0)"
    }

    @Test
    fun `an unsupported outcome is logged as a fallback hop with its reason`() = runTest {
        val transport = LoggingAdbTransport(
            FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Unsupported("ddmlib bridge does not know device"), "", "") }),
            "ddmlib",
            log,
            clock,
        )

        transport.executeText(AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("getprop"))))).outcome

        val entry = log.inCategory(DiagCategory.ADB).last()
        entry.level shouldBe DiagLevel.INFO
        entry.message shouldContain "unsupported"
        entry.fields["reason"] shouldBe "ddmlib bridge does not know device"
    }

    @Test
    fun `a transport failure is a warning`() = runTest {
        val transport = LoggingAdbTransport(
            FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.TransportFailure("adb executable not found"), "", "") }),
            "binary",
            log,
            clock,
        )

        transport.executeText(AdbServerRequest(listOf("devices", "-l")))

        log.inCategory(DiagCategory.ADB).last().level shouldBe DiagLevel.WARN
    }

    @Test
    fun `binary output is forwarded to the sink and its size is logged`() = runTest {
        val received = mutableListOf<Int>()
        val transport = LoggingAdbTransport(
            FakeAdbTransport(binaryScript = { io.github.dkej123.devicecockpit.domain.adb.AdbBinaryScript(listOf(ByteArray(10), ByteArray(5)), AdbOutcome.Completed(0)) }),
            "ddmlib",
            log,
            clock,
        )

        transport.executeBinary(
            AdbDeviceRequest(serial, AdbOperation.Exec(listOf("screencap", "-p"))),
            ByteSink { received += it.size },
        ) shouldBe AdbOutcome.Completed(0)

        received shouldBe listOf(10, 5)
        log.inCategory(DiagCategory.ADB).last().fields["bytes"] shouldBe 15L
    }

    @Test
    fun `stream events pass through and the end of the stream is logged with a line count`() = runTest {
        val transport = LoggingAdbTransport(
            FakeAdbTransport(streamScript = { listOf(AdbStreamEvent.Line("a"), AdbStreamEvent.Line("b"), AdbStreamEvent.Completed(AdbOutcome.Completed(0))) }),
            "binary",
            log,
            clock,
        )

        val events = transport.executeStream(AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("logcat"))))).toList()

        events.size shouldBe 3
        log.inCategory(DiagCategory.ADB).last().fields["lines"] shouldBe 2L
    }
}
