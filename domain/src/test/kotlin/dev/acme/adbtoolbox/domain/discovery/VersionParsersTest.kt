package dev.acme.adbtoolbox.domain.discovery

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VersionParsersTest {

    @Test
    fun `parses the Version line from real adb version output`() {
        val output = """
            Android Debug Bridge version 1.0.41
            Version 35.0.2-12147458
            Installed as /Users/dev/Library/Android/sdk/platform-tools/adb
            Running on Darwin 24.6.0 (arm64)
        """.trimIndent()

        parseAdbVersionOutput(output) shouldBe ToolVersion.of("35.0.2-12147458")
    }

    @Test
    fun `falls back to the bridge protocol version when no Version line is present`() {
        val output = "Android Debug Bridge version 1.0.41"

        parseAdbVersionOutput(output) shouldBe ToolVersion.of("1.0.41")
    }

    @Test
    fun `returns null for adb output with no recognizable version`() {
        parseAdbVersionOutput("command not found").shouldBeNull()
    }

    @Test
    fun `parses the scrcpy version line`() {
        val output = "scrcpy 2.4 <https://github.com/Genymobile/scrcpy>\nusage: scrcpy [options]"

        parseScrcpyVersionOutput(output) shouldBe ToolVersion.of("2.4")
    }

    @Test
    fun `returns null for scrcpy output with no recognizable version`() {
        parseScrcpyVersionOutput("").shouldBeNull()
    }
}
