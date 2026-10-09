package io.github.dkej123.devicecockpit.intellij.icons

import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** Verifies the production icon set v2 (`design/icons/expui`, 2026-10-01) is bundled without modification. */
class DesignIconResourcesTest : BasePlatformTestCase() {

    fun `test every supplied icon has complete light and dark classpath variants`() {
        expectedIconPaths.forEach { path ->
            val resource = javaClass.getResource("/icons/expui/$path")

            assertNotNull("Missing classpath resource /icons/expui/$path", resource)
        }
    }

    fun `test production icon catalog loads every tool window and action icon through IntelliJ`() {
        IconLoader.activate()
        try {
            val icons = AdbToolboxIcons.all
            val loadedImages = icons.map { icon ->
                requireNotNull(IconLoader.toImage(icon)) { "IconLoader did not render $icon" }
            }

            assertEquals(30, icons.size)
            assertEquals(30, icons.distinct().size)
            assertSame(AdbToolboxIcons.toolWindow, icons.first())
            loadedImages.forEach { image ->
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
            Regex("""<toolWindow[^>]*id="ADB Toolbox"[^>]*icon="/icons/expui/toolwindow/adbToolbox\.svg""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(pluginXml),
        )
    }

    fun `test the tool window icon has the New UI 20px stripe sibling in both themes`() {
        listOf("adbToolbox", "adbToolbox@20x20").forEach { name ->
            assertNotNull(javaClass.getResource("/icons/expui/toolwindow/$name.svg"))
            assertNotNull(javaClass.getResource("/icons/expui/toolwindow/${name}_dark.svg"))
        }
    }

    fun `test icons use the exact New UI palette so the IDE can recolor them`() {
        val root = findRepositoryRoot().resolve("design/icons/expui")
        val allowed = setOf("#6C707E", "#CED0D6", "#DB3B4B", "#DB5C5C")
        val foreign = mutableMapOf<String, Set<String>>()
        relativeSvgPaths(root).filter { it.startsWith("views/") || it.startsWith("toolwindow/") }.forEach { path ->
            val colors = Regex("""(?:fill|stroke)="(#[0-9A-Fa-f]{3,6})"""")
                .findAll(Files.readString(root.resolve(path))).map { it.groupValues[1].uppercase() }.toSet()
            // A <mask> (white/black) would be recolored too and fill the cut-outs on the selected stripe button.
            val unexpected = colors - allowed + (if ("<mask" in Files.readString(root.resolve(path))) setOf("<mask>") else emptySet())
            if (unexpected.isNotEmpty()) foreign[path] = unexpected
        }
        assertEquals(emptyMap<String, Set<String>>(), foreign)
    }

    fun `test packaged icon resources are byte identical to the design source`() {
        val repositoryRoot = findRepositoryRoot()
        val designRoot = repositoryRoot.resolve("design/icons/expui")
        val packagedRoot = repositoryRoot.resolve("intellij/src/main/resources/icons/expui")

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
            listOf("adbToolbox", "adbToolbox@20x20").forEach { name ->
                add("toolwindow/$name.svg")
                add("toolwindow/${name}_dark.svg")
            }
            listOf("device", "apps", "network", "logcat").forEach { name ->
                add("views/$name.svg")
                add("views/${name}_dark.svg")
            }
            listOf(
                "a11yAudit",
                "authorize",
                "clearData",
                "density",
                "fontScale",
                "forceStop",
                "language",
                "layoutInspector",
                "location",
                "mcpAgent",
                "mirror",
                "options",
                "overlay",
                "pin",
                "pinned",
                "proxy",
                "record",
                "resetOverrides",
                "restartApp",
                "rotate",
                "screenshot",
                "stopRecording",
                "uninstall",
                "usb",
                "wifi",
            ).forEach { name ->
                add("actions/$name.svg")
                add("actions/${name}_dark.svg")
            }
        }
    }
}
