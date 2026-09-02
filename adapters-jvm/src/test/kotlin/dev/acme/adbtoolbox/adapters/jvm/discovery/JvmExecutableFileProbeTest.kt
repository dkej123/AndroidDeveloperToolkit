package dev.acme.adbtoolbox.adapters.jvm.discovery

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class JvmExecutableFileProbeTest {

    @Test
    fun `an executable regular file validates`() = runTest {
        val file = Files.createTempFile("adb-toolbox-probe", "").toFile()
        file.deleteOnExit()
        file.setExecutable(true)

        JvmExecutableFileProbe().isExecutable(file.absolutePath) shouldBe true
    }

    @Test
    fun `a non-executable regular file does not validate`() = runTest {
        val file = Files.createTempFile("adb-toolbox-probe", "").toFile()
        file.deleteOnExit()
        file.setExecutable(false)

        JvmExecutableFileProbe().isExecutable(file.absolutePath) shouldBe false
    }

    @Test
    fun `a missing path does not validate`() = runTest {
        val missing = File(Files.createTempDirectory("adb-toolbox-probe-dir").toFile(), "does-not-exist")

        JvmExecutableFileProbe().isExecutable(missing.absolutePath) shouldBe false
    }

    @Test
    fun `a directory does not validate even if marked executable`() = runTest {
        val dir = Files.createTempDirectory("adb-toolbox-probe-dir").toFile()
        dir.setExecutable(true)

        JvmExecutableFileProbe().isExecutable(dir.absolutePath) shouldBe false
    }
}
