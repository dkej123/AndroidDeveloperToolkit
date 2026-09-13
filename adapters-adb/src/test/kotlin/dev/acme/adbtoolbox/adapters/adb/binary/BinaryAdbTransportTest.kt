package dev.acme.adbtoolbox.adapters.adb.binary

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.discovery.DiscoveredTool
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolExecutablePath
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolSource
import dev.acme.adbtoolbox.domain.discovery.ToolVersion
import dev.acme.adbtoolbox.domain.process.ByteSink
import dev.acme.adbtoolbox.domain.process.FakeProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// NOTE: every test body is a statement `{ runBlocking { ... } }`, not an expression body — kotest's
// `shouldBe` returns its receiver, so an expression-bodied `@Test fun` would infer a non-Unit
// return type and JUnit5 would silently skip it (see JvmProcessExecutorTest).
class BinaryAdbTransportTest {

    private val serial = DeviceSerial.of("R58N90ABCDE")
    private val wirelessSerial = DeviceSerial.of("192.168.1.42:5555")
    private val discoveredAdb = DiscoveredTool(
        id = ToolId.Adb,
        source = ToolSource.PathFallback,
        path = ToolExecutablePath.of("/opt/platform-tools/adb"),
        version = ToolVersion.of("1.0.41"),
    )

    private fun foundLocator(path: String = discoveredAdb.path.value) = FakeToolLocator {
        DiscoveryOutcome.Found(discoveredAdb.copy(path = ToolExecutablePath.of(path)))
    }

    private fun missingLocator(error: DiscoveryError) = FakeToolLocator { DiscoveryOutcome.Failed(error) }

    private fun transport(locator: FakeToolLocator, executor: ProcessExecutor) = BinaryAdbTransport(locator, executor)

    // --- argument construction -------------------------------------------------------------

    @Test
    fun `device shell request adds exactly one -s serial before the shell command`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor {
                listOf(ProcessEvent.Completed(ProcessOutcome.Completed(0)))
            }
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("getprop"))),
            )

            transport(locator, executor).executeText(request)

            val sent = executor.requests.single().command
            sent.executable shouldBe discoveredAdb.path.value
            sent.arguments shouldContainExactly listOf("-s", serial.toString(), "shell", "getprop")
            sent.arguments.count { it == "-s" } shouldBe 1
        }
    }

    @Test
    fun `device host request adds exactly one serial and does not insert shell or exec-out`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor {
                listOf(ProcessEvent.Completed(ProcessOutcome.Completed(0)))
            }
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Host(listOf("uninstall", "com.acme.shop")),
            )

            transport(locator, executor).executeText(request)

            executor.requests.single().command.arguments shouldContainExactly
                listOf("-s", serial.toString(), "uninstall", "com.acme.shop")
        }
    }

    @Test
    fun `server request carries no serial argument`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor {
                listOf(ProcessEvent.Completed(ProcessOutcome.Completed(0)))
            }
            val request = AdbServerRequest(arguments = listOf("devices", "-l"))

            transport(locator, executor).executeText(request)

            val sent = executor.requests.single().command
            sent.executable shouldBe discoveredAdb.path.value
            sent.arguments shouldContainExactly listOf("devices", "-l")
            sent.arguments.contains("-s") shouldBe false
        }
    }

    @Test
    fun `wireless ip colon port serial is preserved verbatim in the -s argument`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor {
                listOf(ProcessEvent.Completed(ProcessOutcome.Completed(0)))
            }
            val request = AdbDeviceRequest(
                serial = wirelessSerial,
                operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("getprop"))),
            )

            transport(locator, executor).executeText(request)

            executor.requests.single().command.arguments shouldContainExactly
                listOf("-s", "192.168.1.42:5555", "shell", "getprop")
        }
    }

    @Test
    fun `exec-out request adds exactly one -s serial and the literal exec argv`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor {
                listOf(ProcessEvent.Completed(ProcessOutcome.Completed(0)))
            }
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Exec(listOf("screencap", "-p")),
            )
            val sink = ByteSink { }

            transport(locator, executor).executeBinary(request, sink)

            executor.requests.single().command.arguments shouldContainExactly
                listOf("-s", serial.toString(), "exec-out", "screencap", "-p")
        }
    }

    // --- output modes ------------------------------------------------------------------------

    @Test
    fun `executeText returns bounded stdout, stderr and exit code`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor {
                listOf(
                    ProcessEvent.StdoutText("line one"),
                    ProcessEvent.StderrText("warn one"),
                    ProcessEvent.Completed(ProcessOutcome.Completed(0)),
                )
            }
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("getprop"))),
            )

            val result = transport(locator, executor).executeText(request)

            result.outcome shouldBe AdbOutcome.Completed(0)
            result.stdout shouldContain "line one"
            result.stderr shouldContain "warn one"
        }
    }

    @Test
    fun `executeStream preserves stdout and stderr lines then emits a terminal Completed event`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor {
                listOf(
                    ProcessEvent.StdoutText("line one"),
                    ProcessEvent.StderrText("line two"),
                    ProcessEvent.Completed(ProcessOutcome.Completed(0)),
                )
            }
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("logcat"))),
            )

            val events = transport(locator, executor).executeStream(request).toList()

            events shouldContainExactly listOf(
                AdbStreamEvent.Line("line one"),
                AdbStreamEvent.StderrLine("line two"),
                AdbStreamEvent.Completed(AdbOutcome.Completed(0)),
            )
        }
    }

    @Test
    fun `executeBinary streams raw bytes into the sink without text decoding`() {
        runBlocking {
            val locator = foundLocator()
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            val executor = FakeProcessExecutor {
                listOf(
                    ProcessEvent.StdoutBytes(bytes),
                    ProcessEvent.Completed(ProcessOutcome.Completed(0)),
                )
            }
            val request = AdbDeviceRequest(serial = serial, operation = AdbOperation.Exec(listOf("screencap", "-p")))
            val received = mutableListOf<Byte>()
            val sink = ByteSink { chunk -> received += chunk.toList() }

            val outcome = transport(locator, executor).executeBinary(request, sink)

            outcome shouldBe AdbOutcome.Completed(0)
            received shouldContainExactly bytes.toList()
        }
    }

    // --- unsupported operation shapes ---------------------------------------------------------

    @Test
    fun `executeText rejects an exec operation as unsupported without invoking the process executor`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor { error("must not be invoked") }
            val request = AdbDeviceRequest(serial = serial, operation = AdbOperation.Exec(listOf("screencap", "-p")))

            val result = transport(locator, executor).executeText(request)

            result.outcome.shouldBeInstanceOf<AdbOutcome.Unsupported>()
            executor.requests shouldBe emptyList()
        }
    }

    @Test
    fun `executeBinary rejects a shell operation as unsupported without invoking the process executor`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor { error("must not be invoked") }
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("getprop"))),
            )

            val outcome = transport(locator, executor).executeBinary(request, ByteSink { })

            outcome.shouldBeInstanceOf<AdbOutcome.Unsupported>()
            executor.requests shouldBe emptyList()
        }
    }

    @Test
    fun `executeBinary rejects a server request as unsupported without invoking the process executor`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor { error("must not be invoked") }
            val request = AdbServerRequest(arguments = listOf("pair", "192.168.1.5:5555", "123456"))

            val outcome = transport(locator, executor).executeBinary(request, ByteSink { })

            outcome.shouldBeInstanceOf<AdbOutcome.Unsupported>()
            executor.requests shouldBe emptyList()
        }
    }

    // --- missing adb ---------------------------------------------------------------------------

    @Test
    fun `missing adb executable is a transport failure and never invokes the process executor`() {
        runBlocking {
            val error = DiscoveryError.ToolNotFound(ToolId.Adb, attemptedSources = emptyList())
            val locator = missingLocator(error)
            val executor = FakeProcessExecutor { error("must not be invoked") }
            val request = AdbServerRequest(arguments = listOf("devices", "-l"))

            val result = transport(locator, executor).executeText(request)

            result.outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
            executor.requests shouldBe emptyList()
        }
    }

    @Test
    fun `missing adb executable is a transport failure for stream and binary execution too`() {
        runBlocking {
            val error = DiscoveryError.ToolNotFound(ToolId.Adb, attemptedSources = emptyList())
            val executor = FakeProcessExecutor { error("must not be invoked") }
            val streamRequest = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("logcat"))),
            )
            val binaryRequest = AdbDeviceRequest(serial = serial, operation = AdbOperation.Exec(listOf("screencap", "-p")))

            val streamEvents = transport(missingLocator(error), executor).executeStream(streamRequest).toList()
            val binaryOutcome = transport(missingLocator(error), executor).executeBinary(binaryRequest, ByteSink { })

            streamEvents shouldContainExactly listOf(
                AdbStreamEvent.Completed((streamEvents.single() as AdbStreamEvent.Completed).outcome),
            )
            streamEvents.single().shouldBeInstanceOf<AdbStreamEvent.Completed>()
            (streamEvents.single() as AdbStreamEvent.Completed).outcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
            binaryOutcome.shouldBeInstanceOf<AdbOutcome.TransportFailure>()
            executor.requests shouldBe emptyList()
        }
    }

    // --- non-zero exit, timeout, cancellation --------------------------------------------------

    @Test
    fun `non-zero exit code is preserved, never coerced`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor {
                listOf(ProcessEvent.Completed(ProcessOutcome.Completed(17)))
            }
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("pm"))),
            )

            val result = transport(locator, executor).executeText(request)

            result.outcome shouldBe AdbOutcome.Completed(17)
        }
    }

    @Test
    fun `process timeout maps to AdbOutcome TimedOut`() {
        runBlocking {
            val locator = foundLocator()
            val executor = FakeProcessExecutor {
                listOf(ProcessEvent.Completed(ProcessOutcome.TimedOut))
            }
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("logcat"))),
                timeout = 500.milliseconds,
            )

            val result = transport(locator, executor).executeText(request)

            result.outcome shouldBe AdbOutcome.TimedOut
            executor.requests.single().timeout shouldBe 500.milliseconds
        }
    }

    @Test
    fun `cancelling the caller propagates cancellation instead of returning a fabricated outcome`() {
        runBlocking {
            val locator = foundLocator()
            val hangingExecutor = object : ProcessExecutor {
                override fun execute(request: dev.acme.adbtoolbox.domain.process.ProcessRequest): Flow<ProcessEvent> =
                    flow { awaitCancellation() }
            }
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("logcat"))),
            )

            withTimeout(5.seconds) {
                val job = launch(Dispatchers.Default) {
                    transport(locator, hangingExecutor).executeText(request)
                }
                yield()
                job.cancel()
                job.join()
                job.isCancelled shouldBe true
            }
        }
    }
}
