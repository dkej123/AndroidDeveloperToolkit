package dev.acme.adbtoolbox.intellij.diagnostics

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class DiagnosticsBundleWriterTest : BasePlatformTestCase() {

    private lateinit var temp: Path

    override fun setUp() {
        super.setUp()
        temp = Files.createTempDirectory("bundle-test")
    }

    override fun tearDown() {
        try {
            temp.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun entries(zip: Path): Map<String, String> = ZipFile(zip.toFile()).use { file ->
        file.entries().asSequence().associate { it.name to file.getInputStream(it).readBytes().decodeToString() }
    }

    fun `test text, file, tail and directory entries are written and missing inputs are skipped`() {
        val log = Files.writeString(temp.resolve("adb-toolbox.log"), "line 1\nline 2\n")
        val ideaLog = Files.writeString(temp.resolve("idea.log"), "x".repeat(100) + "TAIL")
        val freeze = Files.createDirectories(temp.resolve("threadDumps-freeze-20260924"))
        Files.writeString(freeze.resolve("threadDump-1.txt"), "AWT-EventQueue-0 BLOCKED")

        val zip = DiagnosticsBundleWriter.write(
            temp.resolve("out/bundle.zip"),
            listOf(
                BundleEntry.Text("environment.txt", "ide=Android Studio"),
                BundleEntry.FileCopy("logs/adb-toolbox.log", log),
                BundleEntry.FileTail("ide/idea.log", ideaLog, maxBytes = 4),
                BundleEntry.Directory("ide/threadDumps-freeze-20260924", freeze),
                BundleEntry.FileCopy("logs/missing.log", temp.resolve("nope.log")),
            ),
        )

        val contents = entries(zip)
        contents["environment.txt"] shouldBeEqual "ide=Android Studio"
        contents["logs/adb-toolbox.log"] shouldBeEqual "line 1\nline 2\n"
        contents["ide/idea.log"] shouldBeEqual "TAIL"
        contents["ide/threadDumps-freeze-20260924/threadDump-1.txt"] shouldBeEqual "AWT-EventQueue-0 BLOCKED"
        assertFalse(contents.containsKey("logs/missing.log"))
    }

    fun `test a failing text producer is recorded in the bundle instead of aborting it`() {
        val zip = DiagnosticsBundleWriter.write(
            temp.resolve("bundle.zip"),
            listOf(
                BundleEntry.Lazy("adb.txt") { error("adb hung") },
                BundleEntry.Text("jvm.txt", "heap"),
            ),
        )

        val contents = entries(zip)
        assertTrue(contents.getValue("adb.txt").contains("adb hung"))
        contents["jvm.txt"] shouldBeEqual "heap"
    }

    private infix fun String?.shouldBeEqual(expected: String) = assertEquals(expected, this)
}
