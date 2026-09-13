package dev.acme.adbtoolbox.intellij.icons

import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** Verifies the production icon handoff from task 042 is bundled without modification. */
class DesignIconResourcesTest : BasePlatformTestCase() {

    fun `test every supplied icon has complete light and dark classpath variants`() {
        expectedIconPaths.forEach { path ->
            val resource = javaClass.getResource("/icons/$path")

            assertNotNull("Missing classpath resource /icons/$path", resource)
        }
    }

    fun `test production icon catalog loads every tool window and action icon through IntelliJ`() {
        IconLoader.activate()
        try {
            val icons = AdbToolboxIcons.all
            val loadedImages = icons.map { icon ->
                requireNotNull(IconLoader.toImage(icon)) { "IconLoader did not render $icon" }
            }

            assertEquals(20, icons.size)
            assertEquals(20, icons.distinct().size)
            assertSame(AdbToolboxIcons.toolWindow, icons.first())
            assertEquals(20, loadedImages.first().getWidth(null))
            assertEquals(20, loadedImages.first().getHeight(null))
            loadedImages.drop(1).forEach { image ->
                assertEquals(16, image.getWidth(null))
                assertEquals(16, image.getHeight(null))
            }
        } finally {
            IconLoader.deactivate()
        }
    }

    fun `test marketplace logo is packaged at the IntelliJ metadata location`() {
        listOf("pluginIcon.svg", "pluginIcon_dark.svg").forEach { fileName ->
            val resource = javaClass.getResource("/META-INF/$fileName")

            assertNotNull("Missing Marketplace icon /META-INF/$fileName", resource)
            assertTrue(
                "Marketplace icon differs from supplied design source: $fileName",
                resource!!.readText().trimEnd() ==
                    Files.readString(findRepositoryRoot().resolve("design/icons/$fileName")).trimEnd(),
            )
        }
    }

    fun `test tool window registration uses the supplied production icon`() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()

        assertTrue(
            "ADB Toolbox toolWindow must use the supplied production icon",
            Regex("""<toolWindow[^>]*id="ADB Toolbox"[^>]*icon="/icons/adbToolbox\.svg""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(pluginXml),
        )
    }

    fun `test packaged icon resources are byte identical to the design source`() {
        val repositoryRoot = findRepositoryRoot()
        val designRoot = repositoryRoot.resolve("design/icons")
        val packagedRoot = repositoryRoot.resolve("intellij/src/main/resources/icons")

        assertEquals(expectedIconPaths, relativeSvgPaths(designRoot))
        assertEquals(expectedIconPaths, relativeSvgPaths(packagedRoot))
        expectedIconPaths.forEach { path ->
            assertTrue(
                "Packaged resource differs from design source: $path",
                Files.readAllBytes(designRoot.resolve(path)).contentEquals(Files.readAllBytes(packagedRoot.resolve(path))),
            )
        }
    }

    private fun findRepositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { candidate ->
                candidate.resolve("design/icons").isDirectory() &&
                    candidate.resolve("intellij/src/main/resources").isDirectory()
            }
            ?: error("Cannot locate repository root from ${Path.of("").toAbsolutePath()}")

    private fun relativeSvgPaths(root: Path): Set<String> {
        if (!root.isDirectory()) return emptySet()
        return Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".svg") }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .toList()
                .toSet()
        }
    }

    private companion object {
        val expectedIconPaths = buildSet {
            listOf("adbToolbox", "adbToolbox_16", "pluginIcon", "pluginIcon_16").forEach { name ->
                add("$name.svg")
                add("${name}_dark.svg")
            }
            listOf(
                "authorize",
                "autoscroll",
                "clearData",
                "density",
                "filter",
                "fontScale",
                "forceStop",
                "mirror",
                "options",
                "pause",
                "proxy",
                "record",
                "refresh",
                "restart",
                "resume",
                "screenshot",
                "search",
                "uninstall",
                "wrap",
            ).forEach { name ->
                add("actions/$name.svg")
                add("actions/${name}_dark.svg")
            }
        }
    }
}
