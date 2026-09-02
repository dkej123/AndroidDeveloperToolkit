package dev.acme.adbtoolbox.domain.adb

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Demonstrates the pattern later feature tasks (Apps, Display, Network, Capture, Logcat) follow:
 * a feature-local object pairs a command factory (builds an [AdbDeviceRequest] from typed
 * arguments, never a raw string) with a parser (turns [AdbTextResult] stdout into a domain value
 * or an [AdbParseResult.Malformed]). Nothing here is a shipped feature command — task 003 defines
 * only the seam these feature-local files sit behind ([AdbTransport], [AdbShellCommand],
 * [AdbParseResult]); concrete commands are out of scope until their own feature tasks.
 */
private object SdkVersionPropCommand {
    fun request(serial: DeviceSerial): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("getprop"),
                ShellToken.Value(ShellValue.of("ro.build.version.sdk")),
            ),
        ),
    )

    fun parse(result: AdbTextResult): AdbParseResult<Int> {
        val trimmed = result.stdout.trim()
        val sdk = trimmed.toIntOrNull()
            ?: return AdbParseResult.Malformed(raw = result.stdout, reason = "expected an integer SDK level")
        return AdbParseResult.Parsed(sdk)
    }
}

class FeatureLocalCommandPatternTest {

    @Test
    fun `a feature-local factory builds a serial-scoped request through the shared gateway`() {
        val request = SdkVersionPropCommand.request(DeviceSerial.of("emulator-5554"))

        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "getprop 'ro.build.version.sdk'"
    }

    @Test
    fun `a feature-local parser normalizes well-formed stdout`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(0), stdout = "34\n", stderr = "") },
        )

        val result = SdkVersionPropCommand.parse(
            transport.executeText(SdkVersionPropCommand.request(DeviceSerial.of("emulator-5554"))),
        )

        result shouldBe AdbParseResult.Parsed(34)
    }

    @Test
    fun `a feature-local parser reports malformed output instead of throwing`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(0), stdout = "not-a-number\n", stderr = "") },
        )

        val result = SdkVersionPropCommand.parse(
            transport.executeText(SdkVersionPropCommand.request(DeviceSerial.of("emulator-5554"))),
        )

        result shouldBe AdbParseResult.Malformed(raw = "not-a-number\n", reason = "expected an integer SDK level")
    }
}
