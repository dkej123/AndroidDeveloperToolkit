package dev.acme.adbtoolbox.adapters.jvm.capture

import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class JvmCaptureDestinationTest {

    private fun tempDir(): Path = Files.createTempDirectory("capture-destination-test")

    @Test
    fun `commit writes the exact bytes to the base file name when there is no collision`() = runTest {
        val dir = tempDir()
        val destination = JvmCaptureDestination(dir)

        val target = destination.beginCapture("screen-20260902-101530.png")
        target.sink.write(byteArrayOf(1, 2, 3))
        val location = target.commit()

        location.displayPath shouldBe dir.resolve("screen-20260902-101530.png").toString()
        Files.readAllBytes(Path.of(location.displayPath)) shouldBe byteArrayOf(1, 2, 3)
    }

    @Test
    fun `a name collision resolves to a numbered suffix instead of overwriting the existing file`() = runTest {
        val dir = tempDir()
        Files.write(dir.resolve("screen-20260902-101530.png"), byteArrayOf(9, 9, 9))
        val destination = JvmCaptureDestination(dir)

        val target = destination.beginCapture("screen-20260902-101530.png")
        target.sink.write(byteArrayOf(1, 2, 3))
        val location = target.commit()

        location.displayPath shouldBe dir.resolve("screen-20260902-101530-1.png").toString()
        Files.readAllBytes(dir.resolve("screen-20260902-101530.png")) shouldBe byteArrayOf(9, 9, 9)
        Files.readAllBytes(dir.resolve("screen-20260902-101530-1.png")) shouldBe byteArrayOf(1, 2, 3)
    }

    @Test
    fun `a second collision advances the numbered suffix again`() = runTest {
        val dir = tempDir()
        Files.write(dir.resolve("screen.png"), byteArrayOf(0))
        Files.write(dir.resolve("screen-1.png"), byteArrayOf(0))
        val destination = JvmCaptureDestination(dir)

        val location = destination.beginCapture("screen.png").also { it.sink.write(byteArrayOf(7)) }.commit()

        location.displayPath shouldBe dir.resolve("screen-2.png").toString()
    }

    @Test
    fun `the committed file never appears at its final path until commit is called`() = runTest {
        val dir = tempDir()
        val destination = JvmCaptureDestination(dir)

        val target = destination.beginCapture("screen.png")
        target.sink.write(byteArrayOf(1, 2, 3))

        Files.exists(dir.resolve("screen.png")) shouldBe false
    }

    @Test
    fun `discard removes the partial temp file and leaves no file at the final path`() = runTest {
        val dir = tempDir()
        val destination = JvmCaptureDestination(dir)

        val target = destination.beginCapture("screen.png")
        target.sink.write(byteArrayOf(1, 2, 3))
        target.discard()

        Files.exists(dir.resolve("screen.png")) shouldBe false
        Files.list(dir).use { entries -> entries.count() } shouldBe 0L
    }

    @Test
    fun `discard is idempotent`() = runTest {
        val dir = tempDir()
        val destination = JvmCaptureDestination(dir)

        val target = destination.beginCapture("screen.png")
        target.discard()
        target.discard()

        Files.list(dir).use { entries -> entries.count() } shouldBe 0L
    }

    @Test
    fun `commit after discard fails loudly instead of silently resurrecting the temp file`() = runTest {
        val dir = tempDir()
        val destination = JvmCaptureDestination(dir)

        val target = destination.beginCapture("screen.png")
        target.discard()

        runCatching { target.commit() }.isFailure shouldBe true
    }

    @Test
    fun `beginCapture fails loudly rather than silently succeeding when the directory is not writable`() = runTest {
        val supportsPosixPermissions = runCatching {
            Files.getFileAttributeView(tempDir(), java.nio.file.attribute.PosixFileAttributeView::class.java) != null
        }.getOrDefault(false)
        assumeTrue(supportsPosixPermissions, "requires a POSIX filesystem (skipped on non-POSIX hosts)")

        val dir = tempDir()
        Files.setPosixFilePermissions(dir, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        val destination = JvmCaptureDestination(dir)

        try {
            runCatching { destination.beginCapture("screen.png") }.isFailure shouldBe true
        } finally {
            // Restore write permission so the JVM's own temp-directory cleanup can remove it.
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `beginCapture creates the destination directory if it does not exist yet`() = runTest {
        val dir = tempDir().resolve("nested/captures")
        val destination = JvmCaptureDestination(dir)

        val location = destination.beginCapture("screen.png").also { it.sink.write(byteArrayOf(1)) }.commit()

        Files.exists(Path.of(location.displayPath)) shouldBe true
    }
}
