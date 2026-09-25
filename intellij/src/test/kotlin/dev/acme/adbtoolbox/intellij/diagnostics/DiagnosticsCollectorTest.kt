package dev.acme.adbtoolbox.intellij.diagnostics

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import java.nio.file.Files
import java.util.zip.ZipFile

class DiagnosticsCollectorTest : BasePlatformTestCase() {

    fun `test the bundle contains the log, environment, adb, thread and jvm sections`() {
        DiagnosticsService.getInstance().log.log(DiagLevel.INFO, DiagCategory.LIFECYCLE, "collector-test-marker")
        val target = Files.createTempDirectory("collector").resolve("bundle.zip")

        DiagnosticsCollector.collect(project, target)

        ZipFile(target.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.toString(), names.containsAll(listOf("README.txt", "environment.txt", "adb.txt", "threads.txt", "jvm.txt", "adb-toolbox/adb-toolbox.log")))
            val log = zip.getInputStream(zip.getEntry("adb-toolbox/adb-toolbox.log")).readBytes().decodeToString()
            assertTrue(log.contains("collector-test-marker"))
            val environment = zip.getInputStream(zip.getEntry("environment.txt")).readBytes().decodeToString()
            assertTrue(environment.contains("PATH (login shell)"))
            val adb = zip.getInputStream(zip.getEntry("adb.txt")).readBytes().decodeToString()
            assertTrue(adb, adb.contains("Tool discovery"))
        }
        target.parent.toFile().deleteRecursively()
    }
}
