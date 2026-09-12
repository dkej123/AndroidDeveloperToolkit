package dev.acme.adbtoolbox.adapters.jvm.capture

import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Exercises [containingFolderOf], the pure path-resolution half of [DesktopRevealInFileManager] —
 * never [DesktopRevealInFileManager.reveal] itself, which would pop a real Finder/Explorer window
 * during a test run (adb-development: normal tests use no Finder/Explorer).
 */
class DesktopRevealInFileManagerTest {

    @Test
    fun `resolves the absolute parent folder of a capture's file path`() {
        val parent = containingFolderOf(CaptureLocation(displayPath = "/Users/dev/Desktop/screen.png"))

        parent.toString() shouldBe "/Users/dev/Desktop"
    }

    @Test
    fun `resolves a relative path's parent to an absolute folder`() {
        val parent = containingFolderOf(CaptureLocation(displayPath = "captures/screen.png"))

        parent?.isAbsolute shouldBe true
        parent?.fileName.toString() shouldBe "captures"
    }
}
