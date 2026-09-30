package dev.acme.adbtoolbox.intellij.apps.details

import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.model.AndroidModel
import com.android.tools.idea.model.MergedManifestManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkCatalog
import dev.acme.adbtoolbox.domain.deeplinks.NavigationDeepLinkParser
import dev.acme.adbtoolbox.domain.deeplinks.ProjectDeepLinkProvider
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.jetbrains.android.facet.AndroidFacet

/** Supplies active-variant manifest and Navigation XML metadata from Android Studio's project model. */
class AndroidProjectDeepLinkProvider(private val project: Project) : ProjectDeepLinkProvider {
    override fun load(packageName: String): DeepLinkCatalog? = ReadAction.compute<DeepLinkCatalog?, RuntimeException> {
        val module = ModuleManager.getInstance(project).modules.firstOrNull { candidate ->
            val facet = AndroidFacet.getInstance(candidate) ?: return@firstOrNull false
            AndroidModel.get(facet)?.applicationId == packageName
        } ?: return@compute null
        val facet = AndroidFacet.getInstance(module) ?: return@compute null
        val snapshot = MergedManifestManager.getSnapshot(module)?.takeIf { it.isValid } ?: return@compute null
        val manifest = snapshot.document?.let(::serialize) ?: return@compute null
        val model = GradleAndroidModel.get(facet) ?: return@compute null
        val graphs = linkedMapOf<String, String>()
        model.activeSourceProviders.flatMap { it.resDirectories }.forEach { res ->
            loadNavigationGraphs(res.toPath()).forEach { (name, xml) -> graphs[name] = xml }
        }
        NavigationDeepLinkParser.parse(packageName, manifest, graphs)
    }

    internal fun loadNavigationGraphs(res: Path): Map<String, String> {
        if (!Files.isDirectory(res)) return emptyMap()
        val result = linkedMapOf<String, String>()
        Files.list(res).use { directories ->
            directories.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("navigation") }.forEach { directory ->
                Files.list(directory).use { files ->
                    files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }.forEach { file ->
                        result[file.fileName.toString().removeSuffix(".xml")] = Files.readString(file)
                    }
                }
            }
        }
        return result
    }

    private fun serialize(document: org.w3c.dom.Document): String {
        val output = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }.transform(DOMSource(document), StreamResult(output))
        return output.toString()
    }
}
