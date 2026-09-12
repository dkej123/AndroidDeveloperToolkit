package dev.acme.adbtoolbox.domain.display.density

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * `wm density` reports a "Physical density" line always, and an "Override density" line only when
 * a per-device override is currently applied. This parser must accept the standard AOSP wording,
 * at least one representative OEM wording variant (different case/spacing, plus trailing
 * descriptive text some OEMs append), CRLF line endings, and must not crash on malformed,
 * unsupported-command, or permission-denied stdout/stderr.
 */
class DensityOutputParserTest {

    @Test
    fun `standard AOSP output with no override parses physical density only`() {
        val raw = "Physical density: 420\n"

        parseDensityText(raw) shouldBe DensityParseResult.Parsed(DensityReading(physicalDpi = 420, overrideDpi = null))
    }

    @Test
    fun `standard AOSP output with an override parses both values`() {
        val raw = "Physical density: 420\nOverride density: 560\n"

        parseDensityText(raw) shouldBe
            DensityParseResult.Parsed(DensityReading(physicalDpi = 420, overrideDpi = 560))
    }

    @Test
    fun `CRLF line endings are tolerated`() {
        val raw = "Physical density: 420\r\nOverride density: 560\r\n"

        parseDensityText(raw) shouldBe
            DensityParseResult.Parsed(DensityReading(physicalDpi = 420, overrideDpi = 560))
    }

    @Test
    fun `an OEM wording variant with different case, spacing and trailing notes still parses`() {
        val raw = "physical density:420 (default)\r\noverride density:560 (custom)\r\n"

        parseDensityText(raw) shouldBe
            DensityParseResult.Parsed(DensityReading(physicalDpi = 420, overrideDpi = 560))
    }

    @Test
    fun `blank output is malformed`() {
        parseDensityText("").shouldBeInstanceOf<DensityParseResult.Malformed>()
    }

    @Test
    fun `unrecognizable output is malformed`() {
        val raw = "this device said something unexpected\n"

        val result = parseDensityText(raw)

        result.shouldBeInstanceOf<DensityParseResult.Malformed>()
        (result as DensityParseResult.Malformed).raw shouldBe raw
    }

    @Test
    fun `a physical density line with a non-numeric value is malformed`() {
        parseDensityText("Physical density: not-a-number\n").shouldBeInstanceOf<DensityParseResult.Malformed>()
    }

    @Test
    fun `an unknown-command style response is reported as unsupported, not malformed`() {
        val raw = "Error: unknown command 'density'\n" +
            "Exception occurred while executing 'density':\n" +
            "java.lang.IllegalArgumentException: Bad command: density\n"

        parseDensityText(raw).shouldBeInstanceOf<DensityParseResult.Unsupported>()
    }

    @Test
    fun `a permission denial is reported distinctly from a parse failure`() {
        val raw = "java.lang.SecurityException: Permission Denial: writing to settings requires " +
            "android.permission.WRITE_SECURE_SETTINGS\n"

        parseDensityText(raw).shouldBeInstanceOf<DensityParseResult.PermissionDenied>()
    }
}
