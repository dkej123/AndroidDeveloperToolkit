package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

/**
 * Task 051's structural guard for one supplied scaling invariant: `design/README.md`'s pixel
 * measurements only mean anything once IntelliJ's UI scale is applied, so every literal-pixel
 * border in production UI code must go through a scale-aware factory ([com.intellij.util.ui.JBUI]),
 * never the raw AWT `BorderFactory.createEmptyBorder(nonzero, ...)` (which silently stays at 1x on
 * a 200% display). A zero-only border needs no scaling and is allowed either way.
 */
class UnscaledBorderGuardTest : BasePlatformTestCase() {

    fun `test no production UI file uses an unscaled non-zero empty border literal`() {
        val root = findRepositoryRoot().resolve("intellij/src/main/kotlin")
        val offenders = mutableListOf<String>()
        // Only flags calls whose entire argument list is numeric literals (no identifiers, so no
        // already-scaled `AdbToolboxTheme.Spacing.sN` token) that isn't all zero.
        val call = Regex("""createEmptyBorder\(([^)]*)\)""")
        val numericArgsOnly = Regex("""^[0-9,\s]*$""")
        val allZero = Regex("""^\s*(0\s*(,\s*0\s*)*)?$""")

        Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                call.findAll(text).forEach { match ->
                    val args = match.groupValues[1]
                    if (numericArgsOnly.matches(args) && !allZero.matches(args)) {
                        offenders += "${root.relativize(file)}: createEmptyBorder($args)"
                    }
                }
            }
        }

        assertTrue(
            "Unscaled non-zero BorderFactory.createEmptyBorder(...) found — use JBUI.Borders.empty(...) " +
                "instead so the value scales with the IDE's UI scale: $offenders",
            offenders.isEmpty(),
        )
    }

    private fun findRepositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { candidate ->
                candidate.resolve("design/README.md").let { Files.exists(it) } &&
                    candidate.resolve("intellij/src/main").isDirectory()
            }
            ?: error("Cannot locate repository root from ${Path.of("").toAbsolutePath()}")
}
