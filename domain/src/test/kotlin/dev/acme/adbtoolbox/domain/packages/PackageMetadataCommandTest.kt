package dev.acme.adbtoolbox.domain.packages

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbParseResult
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private fun textResult(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

private const val WELL_FORMED_DEBUGGABLE = """
Packages:
  Package [com.acme.shop] (3f2a9c1):
    userId=10123
    versionName=2.3.1
    versionCode=45
    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
    applicationLabel=Acme Shop
"""

private const val WELL_FORMED_RELEASE = """
Packages:
  Package [com.acme.other] (a1b2c3):
    versionName=1.0.0
    flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
    applicationLabel=日本アプリ
"""

class PackageMetadataCommandTest {

    @Test
    fun `renders dumpsys package pkg`() {
        val request = PackageMetadataCommand.request(DeviceSerial.of("emulator-5554"), "com.acme.shop")

        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "dumpsys package 'com.acme.shop'"
    }

    @Test
    fun `parses application label and a debuggable flag`() {
        val result = PackageMetadataCommand.parse("com.acme.shop", textResult(WELL_FORMED_DEBUGGABLE))

        result shouldBe AdbParseResult.Parsed(PackageMetadata(label = "Acme Shop", isDebuggable = true))
    }

    @Test
    fun `parses a non-ascii application label and a release (non-debuggable) flag set`() {
        val result = PackageMetadataCommand.parse("com.acme.other", textResult(WELL_FORMED_RELEASE))

        result shouldBe AdbParseResult.Parsed(
            PackageMetadata(label = "日本アプリ", isDebuggable = false),
        )
    }

    @Test
    fun `a missing application label field parses as a null label rather than malformed`() {
        val output = """
            Packages:
              Package [com.acme.shop] (3f2a9c1):
                flags=[ DEBUGGABLE ]
        """.trimIndent()

        val result = PackageMetadataCommand.parse("com.acme.shop", textResult(output))

        result shouldBe AdbParseResult.Parsed(PackageMetadata(label = null, isDebuggable = true))
    }

    @Test
    fun `a missing flags field parses as an unknown (null) debuggable flag rather than false`() {
        val output = """
            Packages:
              Package [com.acme.shop] (3f2a9c1):
                applicationLabel=Acme Shop
        """.trimIndent()

        val result = PackageMetadataCommand.parse("com.acme.shop", textResult(output))

        result shouldBe AdbParseResult.Parsed(PackageMetadata(label = "Acme Shop", isDebuggable = null))
    }

    @Test
    fun `blank dumpsys output is malformed rather than a false negative`() {
        val result = PackageMetadataCommand.parse("com.acme.shop", textResult(""))

        result.shouldBeMalformedFor("com.acme.shop")
    }

    private fun AdbParseResult<PackageMetadata>.shouldBeMalformedFor(packageName: String) {
        val malformed = this as AdbParseResult.Malformed
        malformed.reason shouldBe "empty dumpsys package output for $packageName"
    }
}
