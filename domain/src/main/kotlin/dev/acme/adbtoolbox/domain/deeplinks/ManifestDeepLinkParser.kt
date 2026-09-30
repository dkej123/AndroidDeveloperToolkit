package dev.acme.adbtoolbox.domain.deeplinks

/** Parser for the final XML printed by `apkanalyzer manifest print`. */
object ManifestDeepLinkParser {
    private val packageRegex = Regex("""<manifest\b[^>]*\bpackage\s*=\s*[\"']([^\"']+)[\"']""")
    private val tagRegex = Regex("""<\s*(/?)\s*(activity-alias|activity|intent-filter|action|category|data)\b([^>]*?)(/?)\s*>""")
    private val attrRegex = Regex("""(?:android:)?([\w.-]+)\s*=\s*([\"'])(.*?)\2""")

    fun parse(xml: String, apkSource: String? = null): DeepLinkCatalog {
        val packageName = packageRegex.find(xml)?.groupValues?.get(1).orEmpty()
        val targets = mutableListOf<DeepLinkTarget>()
        var component: Component? = null
        var filter: Filter? = null
        tagRegex.findAll(xml).forEach { match ->
            val closing = match.groupValues[1].isNotEmpty()
            val tag = match.groupValues[2]
            val attrs = attributes(match.groupValues[3])
            val selfClosing = match.groupValues[4].isNotEmpty()
            when {
                !closing && (tag == "activity" || tag == "activity-alias") -> component = Component(tag, attrs)
                closing && (tag == "activity" || tag == "activity-alias") -> {
                    component?.finish(packageName)?.let(targets::add)
                    component = null
                }
                !closing && tag == "intent-filter" && component != null -> filter = Filter(attrs["autoVerify"] == "true")
                closing && tag == "intent-filter" -> {
                    filter?.let { component?.filters?.add(it) }
                    filter = null
                }
                !closing && tag == "action" -> if (attrs["name"] == "android.intent.action.VIEW") filter?.view = true
                !closing && tag == "category" -> when (attrs["name"]) {
                    "android.intent.category.BROWSABLE" -> filter?.browsable = true
                    "android.intent.category.DEFAULT" -> filter?.default = true
                }
                !closing && tag == "data" -> filter?.add(attrs)
            }
            if (selfClosing && tag == "intent-filter") {
                filter?.let { component?.filters?.add(it) }
                filter = null
            }
        }
        return DeepLinkCatalog(packageName, targets.distinct(), apkSources = listOfNotNull(apkSource))
    }

    private fun attributes(raw: String): Map<String, String> = attrRegex.findAll(raw).associate {
        it.groupValues[1] to unescape(it.groupValues[3])
    }

    private fun unescape(value: String) = value.replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'")

    private fun qualify(packageName: String, name: String?): String = when {
        name == null -> ""
        name.startsWith('.') -> packageName + name
        '.' !in name -> "$packageName.$name"
        else -> name
    }

    private data class Component(val tag: String, val attrs: Map<String, String>, val filters: MutableList<Filter> = mutableListOf()) {
        fun finish(packageName: String): DeepLinkTarget? {
            if (attrs["exported"] != "true") return null
            val valid = filters.filter { it.view && it.browsable && it.hasUriData() }
            if (valid.isEmpty()) return null
            return DeepLinkTarget(
                componentName = qualify(packageName, attrs["name"]),
                kind = if (tag == "activity-alias") DeepLinkTargetKind.ACTIVITY_ALIAS else DeepLinkTargetKind.ACTIVITY,
                targetActivity = attrs["targetActivity"]?.let { qualify(packageName, it) },
                patterns = valid.map(Filter::pattern),
                hasDefaultCategory = valid.all { it.default },
            )
        }
    }

    private data class Filter(
        val autoVerify: Boolean,
        var view: Boolean = false,
        var browsable: Boolean = false,
        var default: Boolean = false,
        val schemes: MutableSet<String> = linkedSetOf(),
        val hosts: MutableSet<String> = linkedSetOf(),
        val ports: MutableSet<String> = linkedSetOf(),
        val mimeTypes: MutableSet<String> = linkedSetOf(),
        val ssp: MutableList<UriMatcher> = mutableListOf(),
        val paths: MutableList<UriMatcher> = mutableListOf(),
        val queries: MutableList<UriMatcher> = mutableListOf(),
        val fragments: MutableList<UriMatcher> = mutableListOf(),
    ) {
        fun add(a: Map<String, String>) {
            a["scheme"]?.let(schemes::add); a["host"]?.let(hosts::add); a["port"]?.let(ports::add); a["mimeType"]?.let(mimeTypes::add)
            add(a, "ssp", UriMatcherKind.SSP_LITERAL, ssp); add(a, "sspPrefix", UriMatcherKind.SSP_PREFIX, ssp)
            add(a, "sspPattern", UriMatcherKind.SSP_PATTERN, ssp); add(a, "sspAdvancedPattern", UriMatcherKind.SSP_ADVANCED_PATTERN, ssp)
            add(a, "path", UriMatcherKind.PATH_LITERAL, paths); add(a, "pathPrefix", UriMatcherKind.PATH_PREFIX, paths)
            add(a, "pathSuffix", UriMatcherKind.PATH_SUFFIX, paths); add(a, "pathPattern", UriMatcherKind.PATH_PATTERN, paths)
            add(a, "pathAdvancedPattern", UriMatcherKind.PATH_ADVANCED_PATTERN, paths)
            add(a, "query", UriMatcherKind.QUERY_LITERAL, queries); add(a, "queryPattern", UriMatcherKind.QUERY_PATTERN, queries)
            add(a, "queryAdvancedPattern", UriMatcherKind.QUERY_ADVANCED_PATTERN, queries)
            add(a, "fragment", UriMatcherKind.FRAGMENT_LITERAL, fragments); add(a, "fragmentPattern", UriMatcherKind.FRAGMENT_PATTERN, fragments)
            add(a, "fragmentAdvancedPattern", UriMatcherKind.FRAGMENT_ADVANCED_PATTERN, fragments)
        }
        fun hasUriData() = schemes.isNotEmpty() || hosts.isNotEmpty() || mimeTypes.isNotEmpty() || ssp.isNotEmpty()
        fun pattern() = UriPattern(schemes, hosts, ports, mimeTypes, ssp, paths, queries, fragments, autoVerify)
        private fun add(a: Map<String, String>, key: String, kind: UriMatcherKind, target: MutableList<UriMatcher>) {
            a[key]?.let { target += UriMatcher(kind, it) }
        }
    }
}
