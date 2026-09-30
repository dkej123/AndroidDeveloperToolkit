package dev.acme.adbtoolbox.domain.deeplinks

/** Reads Navigation XML metadata without introducing Android/IDE APIs into the domain module. */
object NavigationDeepLinkParser {
    private val navigationRef = Regex("""@navigation/([A-Za-z0-9_]+)""")
    private val placeholder = Regex("""\{([A-Za-z][A-Za-z0-9_]*)}""")

    fun parse(packageName: String, mergedManifest: String, graphs: Map<String, String>): DeepLinkCatalog {
        val manifestCatalog = ManifestDeepLinkParser.parse(mergedManifest)
        val manifestTree = XmlNode.parse(mergedManifest)
        val parametersByActivity = linkedMapOf<String, MutableList<DeepLinkParameter>>()

        manifestTree.descendants().filter { it.name == "activity" || it.name == "activity-alias" }.forEach { activity ->
            val component = qualify(packageName, activity.attributes["name"])
            val graphNames = activity.children.filter { it.name == "nav-graph" }
                .mapNotNull { navigationRef.find(it.attributes["value"].orEmpty())?.groupValues?.get(1) }
            graphNames.forEach { graph ->
                collectGraph(graph, graphs, emptySet()).forEach { parameter ->
                    val target = parametersByActivity.getOrPut(component) { mutableListOf() }
                    if (parameter !in target) target += parameter
                }
            }
        }

        return manifestCatalog.copy(targets = manifestCatalog.targets.map { target ->
            target.copy(
                sources = setOf(DeepLinkSource.PROJECT),
                parameters = parametersByActivity[target.componentName].orEmpty(),
            )
        })
    }

    private fun collectGraph(name: String, graphs: Map<String, String>, visited: Set<String>): List<DeepLinkParameter> {
        if (name in visited) return emptyList()
        val tree = graphs[name]?.let(XmlNode::parse) ?: return emptyList()
        val nextVisited = visited + name
        return collectDestinations(tree, emptyMap()) + tree.descendants().filter { it.name == "include" }
            .mapNotNull { navigationRef.find(it.attributes["graph"].orEmpty())?.groupValues?.get(1) }
            .flatMap { collectGraph(it, graphs, nextVisited) }
    }

    private fun collectDestinations(node: XmlNode, inherited: Map<String, Argument>): List<DeepLinkParameter> {
        val ownArguments = node.children.filter { it.name == "argument" }.mapNotNull(Argument::from).associateBy(Argument::name)
        val arguments = inherited + ownArguments
        val direct = node.children.filter { it.name == "deepLink" }.flatMap { link ->
            val uri = link.attributes["uri"].orEmpty()
            placeholder.findAll(uri).map { it.groupValues[1] }.distinct().map { name ->
                val argument = arguments[name]
                DeepLinkParameter(
                    name = name,
                    location = location(uri, name),
                    type = argument?.type,
                    source = DeepLinkSource.PROJECT,
                    requirement = if (argument?.defaultValue != null || argument?.nullable == true) {
                        ParameterRequirement.OPTIONAL
                    } else {
                        ParameterRequirement.REQUIRED
                    },
                    defaultValue = argument?.defaultValue,
                    nullable = argument?.nullable == true,
                )
            }.toList()
        }
        val nested = node.children.filter { it.name in DESTINATIONS }.flatMap { collectDestinations(it, arguments) }
        return (direct + nested).distinct()
    }

    private fun location(uri: String, name: String): ParameterLocation {
        val marker = "{$name}"
        val fragmentAt = uri.indexOf('#')
        val queryAt = uri.indexOf('?')
        val markerAt = uri.indexOf(marker)
        return when {
            fragmentAt >= 0 && markerAt > fragmentAt -> ParameterLocation.FRAGMENT
            queryAt >= 0 && markerAt > queryAt && (fragmentAt < 0 || markerAt < fragmentAt) -> ParameterLocation.QUERY
            else -> ParameterLocation.PATH
        }
    }

    private fun qualify(packageName: String, name: String?): String = when {
        name == null -> ""
        name.startsWith('.') -> packageName + name
        '.' !in name -> "$packageName.$name"
        else -> name
    }

    private data class Argument(val name: String, val type: String?, val defaultValue: String?, val nullable: Boolean) {
        companion object {
            fun from(node: XmlNode): Argument? = node.attributes["name"]?.let {
                Argument(it, node.attributes["argType"], node.attributes["defaultValue"], node.attributes["nullable"] == "true")
            }
        }
    }

    private data class XmlNode(
        val name: String,
        val attributes: Map<String, String> = emptyMap(),
        val children: MutableList<XmlNode> = mutableListOf(),
    ) {
        fun descendants(): Sequence<XmlNode> = sequence {
            children.forEach { child -> yield(child); yieldAll(child.descendants()) }
        }

        companion object {
            private val tags = Regex("""<\s*(/?)\s*([A-Za-z][\w.-]*)([^>]*?)(/?)\s*>""")
            private val attrs = Regex("""(?:[A-Za-z][\w.-]*:)?([A-Za-z][\w.-]*)\s*=\s*(["'])(.*?)\2""")

            fun parse(xml: String): XmlNode {
                val root = XmlNode("root")
                val stack = mutableListOf(root)
                tags.findAll(xml).forEach { match ->
                    val closing = match.groupValues[1].isNotEmpty()
                    val name = match.groupValues[2]
                    if (name.startsWith("?") || name.startsWith("!")) return@forEach
                    if (closing) {
                        if (stack.size > 1) stack.removeAt(stack.lastIndex)
                    } else {
                        val attributes = attrs.findAll(match.groupValues[3]).associate {
                            it.groupValues[1] to unescape(it.groupValues[3])
                        }
                        val node = XmlNode(name, attributes)
                        stack.last().children += node
                        if (match.groupValues[4].isEmpty()) stack += node
                    }
                }
                return root
            }

            private fun unescape(value: String) = value.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'")
        }
    }

    private val DESTINATIONS = setOf("navigation", "fragment", "activity", "dialog")
}

object ProjectDeepLinkEnrichment {
    fun merge(installed: DeepLinkCatalog, project: DeepLinkCatalog?): DeepLinkCatalog {
        if (project == null || project.packageName != installed.packageName) return installed
        val remaining = project.targets.associateBy { it.componentName }.toMutableMap()
        val merged = installed.targets.map { installedTarget ->
            val projectTarget = remaining.remove(installedTarget.componentName) ?: return@map installedTarget
            installedTarget.copy(
                sources = installedTarget.sources + DeepLinkSource.PROJECT,
                parameters = (installedTarget.parameters + projectTarget.parameters).distinct(),
            )
        }
        val projectOnly = remaining.values.map { it.copy(sources = it.sources + DeepLinkSource.RUNTIME_UNKNOWN) }
        return installed.copy(targets = merged + projectOnly)
    }
}

fun interface ProjectDeepLinkProvider {
    fun load(packageName: String): DeepLinkCatalog?
}
