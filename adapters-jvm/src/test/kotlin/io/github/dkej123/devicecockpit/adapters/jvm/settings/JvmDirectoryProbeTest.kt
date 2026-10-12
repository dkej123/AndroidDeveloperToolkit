package io.github.dkej123.devicecockpit.adapters.jvm.settings

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class JvmDirectoryProbeTest {

    @Test
    fun `an existing directory validates`() = runTest {
        val dir = Files.createTempDirectory("device-cockpit-dir-probe").toFile()

        JvmDirectoryProbe().isValidDirectory(dir.absolutePath) shouldBe true
    }

    @Test
    fun `a missing path does not validate`() = runTest {
        val missing = File(Files.createTempDirectory("device-cockpit-dir-probe").toFile(), "does-not-exist")

        JvmDirectoryProbe().isValidDirectory(missing.absolutePath) shouldBe false
    }

    @Test
    fun `a regular file does not validate as a directory`() = runTest {
        val file = Files.createTempFile("device-cockpit-dir-probe", "").toFile()
        file.deleteOnExit()

        JvmDirectoryProbe().isValidDirectory(file.absolutePath) shouldBe false
    }
}
