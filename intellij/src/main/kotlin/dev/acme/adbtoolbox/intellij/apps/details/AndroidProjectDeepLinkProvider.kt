package dev.acme.adbtoolbox.intellij.apps.details

import com.android.tools.idea.model.AndroidModel
import com.android.tools.idea.model.MergedManifestManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkCatalog
import dev.acme.adbtoolbox.domain.deeplinks.NavigationDeepLinkParser
import dev.acme.adbtoolbox.domain.deeplinks.ProjectDeepLinkProvider
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.jetbrains.android.facet.AndroidFacet

/** Supplies active-variant manifest and Navigation XML metadata from Android Studio's project model. */
class AndroidProjectDeepLinkProvider(private val project: Project) : ProjectDeepLinkProvider {
    override fun load(packageName: String): DeepLinkCatalog? {
        val module = ReadAction.compute<Module?, RuntimeException> {
            ModuleManager.getInstance(project).modules.firstOrNull { candidate ->
                val facet = AndroidFacet.getInstance(candidate) ?: return@firstOrNull false
                AndroidModel.get(facet)?.applicationId == packageName
            }
        } ?: return null
        // The cached snapshot when there is one, else wait (outside any read action) for a fresh
        // merge — what the deprecated `MergedManifestManager.getSnapshot(module)` did internally.
        val supplier = MergedManifestManager.getMergedManifestSupplier(module)
        val snapshot = (supplier.now ?: supplier.get().get(MANIFEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            ?.takeIf { it.isValid } ?: return null
        return ReadAction.compute<DeepLinkCatalog?, RuntimeException> {
            val facet = AndroidFacet.getInstance(module) ?: return@compute null
            val manifest = snapshot.document?.let(::serialize) ?: return@compute null
            val model = AndroidModel.get(facet) ?: return@compute null
            val graphs = linkedMapOf<String, String>()
            activeResDirectories(model).forEach { res ->
                loadNavigationGraphs(res.toPath()).forEach { (name, xml) -> graphs[name] = xml }
            }
            NavigationDeepLinkParser.parse(packageName, manifest, graphs)
        }
    }

    /**
     * The active variant's `res` directories: `GradleAndroidModel.activeSourceProviders` →
     * `IdeSourceProvider.resDirectories`, looked up by name. The Android plugin turned
     * `GradleAndroidModel` from a class into an interface and `IdeSourceProvider` the other way
     * round between the IDE versions this plugin supports, so any compiled call to them fails with
     * `IncompatibleClassChangeError` on one side of that change; reflection is indifferent to it.
     * Anything unexpected (a non-Gradle model, a renamed getter) means no Navigation graphs.
     */
    internal fun activeResDirectories(model: Any): List<java.io.File> = try {
        val providers = callGetter(model, "getActiveSourceProviders") as? Iterable<*> ?: emptyList<Any>()
        providers.filterNotNull().flatMap { provider: Any ->
            val dirs = callGetter(provider, "getResDirectories") as? Iterable<*> ?: emptyList<Any>()
            dirs.filterIsInstance<java.io.File>()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun callGetter(target: Any, name: String): Any? {
        val method = target.javaClass.methods.first { it.name == name && it.parameterCount == 0 }
        // The getter's declaring class may be a non-public implementation class.
        method.isAccessible = true
        return method.invoke(target)
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

private const val MANIFEST_TIMEOUT_SECONDS = 10L
