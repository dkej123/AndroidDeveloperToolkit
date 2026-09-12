package dev.acme.adbtoolbox.domain.packages

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbParseResult
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

private fun textResult(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

class PackageListCommandTest {

    @Test
    fun `user scope renders pm list packages -3`() {
        val request = PackageListCommand.request(DeviceSerial.of("emulator-5554"), PackageListScope.User)

        (request.operation as AdbOperation.Shell).command.render() shouldBe "pm list packages -3"
    }

    @Test
    fun `all scope renders pm list packages with no extra flag`() {
        val request = PackageListCommand.request(DeviceSerial.of("emulator-5554"), PackageListScope.All)

        (request.operation as AdbOperation.Shell).command.render() shouldBe "pm list packages"
    }

    @Test
    fun `parses package-prefixed lines`() {
        val output = "package:com.acme.shop\npackage:com.android.systemui\n"

        val results = PackageListCommand.parse(textResult(output))

        results shouldBe listOf(
            AdbParseResult.Parsed("com.acme.shop"),
            AdbParseResult.Parsed("com.android.systemui"),
        )
    }

    @Test
    fun `handles CRLF line endings`() {
        val output = "package:com.acme.shop\r\npackage:com.android.systemui\r\n"

        val results = PackageListCommand.parse(textResult(output))

        results shouldBe listOf(
            AdbParseResult.Parsed("com.acme.shop"),
            AdbParseResult.Parsed("com.android.systemui"),
        )
    }

    @Test
    fun `trims surrounding whitespace around a package line`() {
        val output = "   package:com.acme.shop   \n"

        val results = PackageListCommand.parse(textResult(output))

        results shouldBe listOf(AdbParseResult.Parsed("com.acme.shop"))
    }

    @Test
    fun `blank output parses to an empty list without throwing`() {
        PackageListCommand.parse(textResult("")) shouldBe emptyList()
        PackageListCommand.parse(textResult("   \n  \n")) shouldBe emptyList()
    }

    @Test
    fun `a line without the package prefix is malformed but does not abort the rest of the parse`() {
        val output = "package:com.acme.shop\nunexpected garbage line\npackage:com.android.systemui\n"

        val results = PackageListCommand.parse(textResult(output))

        results.size shouldBe 3
        results[0].shouldBeInstanceOf<AdbParseResult.Parsed<String>>()
        val malformed = results[1] as AdbParseResult.Malformed
        malformed.raw shouldBe "unexpected garbage line"
        results[2].shouldBeInstanceOf<AdbParseResult.Parsed<String>>()
    }

    @Test
    fun `a package-prefixed line with a blank name is malformed`() {
        val results = PackageListCommand.parse(textResult("package:   \n"))

        results.size shouldBe 1
        results[0].shouldBeInstanceOf<AdbParseResult.Malformed>()
    }

    @Test
    fun `non-ascii garbage lines do not crash parsing`() {
        val output = "package:com.acme.shop\n日本語のテキスト\npackage:com.acme.other\n"

        val results = PackageListCommand.parse(textResult(output))

        results.filterIsInstance<AdbParseResult.Parsed<String>>().map { it.value } shouldBe
            listOf("com.acme.shop", "com.acme.other")
        results[1].shouldBeInstanceOf<AdbParseResult.Malformed>()
    }

    @Test
    fun `duplicate package names are deduplicated with the last occurrence winning position preserved`() {
        val output = "package:com.acme.shop\npackage:com.acme.other\npackage:com.acme.shop\n"

        val names = PackageListCommand.distinctPackageNames(textResult(output))

        names shouldBe listOf("com.acme.shop", "com.acme.other")
    }
}
