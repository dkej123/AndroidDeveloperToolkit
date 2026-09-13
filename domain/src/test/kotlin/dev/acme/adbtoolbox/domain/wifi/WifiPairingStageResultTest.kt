package dev.acme.adbtoolbox.domain.wifi

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [parsePairStageResult]/[parseConnectStageResult] normalize a raw [AdbTextResult] into a
 * [WifiPairingStageResult] the same way [dev.acme.adbtoolbox.domain.apps]'s uninstall/proxy
 * parsers do: exit code and diagnostics text decide success/failure, and every non-[AdbOutcome
 * .Completed] outcome maps to its own explicit case rather than being swallowed as a failure.
 */
class WifiPairingStageResultTest {

    @Test
    fun `a completed pair call reporting success text is Success`() {
        val result = AdbTextResult(AdbOutcome.Completed(0), stdout = "Successfully paired to 192.168.1.42:37000 [guid=adb-1]", stderr = "")

        parsePairStageResult(result) shouldBe WifiPairingStageResult.Success
    }

    @Test
    fun `a completed pair call with a non-zero exit code is Failure`() {
        val result = AdbTextResult(AdbOutcome.Completed(1), stdout = "", stderr = "Failed: Wrong pairing code")

        val stage = parsePairStageResult(result)

        stage.shouldBeInstanceOf<WifiPairingStageResult.Failure>()
        (stage as WifiPairingStageResult.Failure).reason shouldBe "Failed: Wrong pairing code"
    }

    @Test
    fun `a completed pair call with an unknown exit code and no success text is Failure`() {
        val result = AdbTextResult(AdbOutcome.Completed(exitCode = null), stdout = "", stderr = "")

        parsePairStageResult(result).shouldBeInstanceOf<WifiPairingStageResult.Failure>()
    }

    @Test
    fun `a completed connect call reporting connected text is Success`() {
        val result = AdbTextResult(AdbOutcome.Completed(0), stdout = "connected to 192.168.1.42:5555", stderr = "")

        parseConnectStageResult(result) shouldBe WifiPairingStageResult.Success
    }

    @Test
    fun `a completed connect call reporting a failure phrase is Failure`() {
        val result = AdbTextResult(AdbOutcome.Completed(1), stdout = "", stderr = "failed to connect to '192.168.1.42:5555'")

        val stage = parseConnectStageResult(result)

        stage.shouldBeInstanceOf<WifiPairingStageResult.Failure>()
    }

    @Test
    fun `a timed-out outcome maps to TimedOut`() {
        val result = AdbTextResult(AdbOutcome.TimedOut, stdout = "", stderr = "")

        parsePairStageResult(result) shouldBe WifiPairingStageResult.TimedOut
    }

    @Test
    fun `a cancelled outcome maps to Cancelled`() {
        val result = AdbTextResult(AdbOutcome.Cancelled, stdout = "", stderr = "")

        parsePairStageResult(result) shouldBe WifiPairingStageResult.Cancelled
    }

    @Test
    fun `a transport failure outcome maps to Failure with its reason`() {
        val result = AdbTextResult(AdbOutcome.TransportFailure("adb executable not found"), stdout = "", stderr = "")

        val stage = parsePairStageResult(result)

        stage.shouldBeInstanceOf<WifiPairingStageResult.Failure>()
        (stage as WifiPairingStageResult.Failure).reason shouldBe "adb executable not found"
    }

    @Test
    fun `an unsupported outcome maps to Failure with its reason`() {
        val result = AdbTextResult(AdbOutcome.Unsupported("ddmlib has no pairing equivalent"), stdout = "", stderr = "")

        val stage = parseConnectStageResult(result)

        stage.shouldBeInstanceOf<WifiPairingStageResult.Failure>()
        (stage as WifiPairingStageResult.Failure).reason shouldBe "ddmlib has no pairing equivalent"
    }
}
