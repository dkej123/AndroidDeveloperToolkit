package dev.acme.adbtoolbox.domain.discovery

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ToolPathsTest {

    @Test
    fun `adb executable name has no extension on macOS and Linux`() {
        executableFileName(ToolId.Adb, OperatingSystem.MacOs) shouldBe "adb"
        executableFileName(ToolId.Adb, OperatingSystem.Linux) shouldBe "adb"
    }

    @Test
    fun `adb executable name gets an exe extension on Windows`() {
        executableFileName(ToolId.Adb, OperatingSystem.Windows) shouldBe "adb.exe"
    }

    @Test
    fun `scrcpy executable name follows the same platform rule`() {
        executableFileName(ToolId.Scrcpy, OperatingSystem.MacOs) shouldBe "scrcpy"
        executableFileName(ToolId.Scrcpy, OperatingSystem.Windows) shouldBe "scrcpy.exe"
    }

    @Test
    fun `joins with a forward slash on macOS and Linux`() {
        joinPath("/opt/platform-tools", "adb", OperatingSystem.MacOs) shouldBe "/opt/platform-tools/adb"
        joinPath("/opt/platform-tools/", "adb", OperatingSystem.Linux) shouldBe "/opt/platform-tools/adb"
    }

    @Test
    fun `joins with a backslash on Windows`() {
        joinPath("""C:\Android\platform-tools""", "adb.exe", OperatingSystem.Windows) shouldBe
            """C:\Android\platform-tools\adb.exe"""
    }
}
