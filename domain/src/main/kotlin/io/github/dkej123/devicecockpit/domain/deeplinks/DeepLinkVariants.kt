package io.github.dkej123.devicecockpit.domain.deeplinks

/** One parameter of a [DeepLinkVariant]'s template, written `{name}` in it. */
data class VariantParameter(
    val name: String,
    val location: ParameterLocation,
    val requirement: ParameterRequirement,
    val type: String? = null,
    val defaultValue: String? = null,
)

/**
 * One concrete deep link an activity accepts: a single scheme, host and path of an intent filter
 * (filters multiply their `<data>` values), as a [template] with `{name}` slots for the parts the
 * filter leaves open. [fromNavigation]: taken from a Navigation graph's `<deepLink>`, so names,
 * types and query parameters are exact; otherwise they are inferred from the manifest's wildcards.
 */
data class DeepLinkVariant(
    val componentName: String,
    val template: String,
    val parameters: List<VariantParameter>,
    val autoVerify: Boolean = false,
    val fromNavigation: Boolean = false,
    /** A `pathAdvancedPattern` regex this variant can only approximate; shown so the user can follow it. */
    val advancedPattern: String? = null,
)

/** A URI built from a template; [missing] lists required parameters still empty (left as `{name}`). */
data class DeepLinkUri(val uri: String, val missing: List<String>)

object DeepLinkVariants {
    private val placeholder = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)}""")

    fun expand(target: DeepLinkTarget): List<DeepLinkVariant> {
        val navigation = target.navigationUris.distinct().map { uri -> navigationVariant(target, uri) }
        val manifest = target.patterns.flatMap { pattern -> manifestVariants(target.componentName, pattern) }
            .filterNot { variant -> navigation.any { it.covers(variant) } }
        // Filters often list `.*\\..*\\.ext` variants to match more dots; the simplest already covers them.
        return (navigation + manifest).distinctBy { it.template }.distinctBy { it.componentName to shape(it.template) }
    }

    private fun shape(template: String): String =
        placeholder.replace(template, "{}").replace(Regex("""(\{}\.)+\{}"""), "{}")

    /**
     * Fills [template] with [values] (percent-encoded), drops `key={param}` query pairs left empty,
     * and appends [extraQuery]. Empty path slots in [optional] are dropped; other empty ones stay as
     * `{name}` and are reported as missing.
     */
    fun build(
        template: String,
        values: Map<String, String>,
        extraQuery: List<Pair<String, String>>,
        optional: Set<String> = emptySet(),
    ): DeepLinkUri {
        val missing = mutableListOf<String>()
        val queryAt = template.indexOf('?')
        val head = if (queryAt >= 0) template.substring(0, queryAt) else template
        val query = if (queryAt >= 0) template.substring(queryAt + 1) else ""
        val path = placeholder.replace(head) { match ->
            val name = match.groupValues[1]
            val value = values[name].orEmpty()
            when {
                value.isNotEmpty() -> encode(value, keep = "/:@!$&'()*+,;=")
                name in optional -> ""
                else -> match.value.also { missing += name }
            }
        }
        val pairs = query.split('&').filter(String::isNotEmpty).mapNotNull { pair ->
            val name = placeholder.find(pair)?.groupValues?.get(1) ?: return@mapNotNull pair
            val value = values[name].orEmpty()
            if (value.isEmpty()) null else placeholder.replace(pair) { encodeQuery(value) }
        } + extraQuery.filter { it.first.isNotBlank() }.map { (key, value) -> "${encodeQuery(key)}=${encodeQuery(value)}" }
        return DeepLinkUri(if (pairs.isEmpty()) path else "$path?${pairs.joinToString("&")}", missing)
    }

    private fun navigationVariant(target: DeepLinkTarget, uri: String): DeepLinkVariant {
        val known = target.parameters.associateBy { it.name }
        val parameters = placeholder.findAll(uri).map { it.groupValues[1] }.distinct().map { name ->
            val argument = known[name]
            val location = location(uri, name)
            VariantParameter(
                name = name,
                location = location,
                requirement = argument?.requirement
                    ?: if (location == ParameterLocation.QUERY) ParameterRequirement.OPTIONAL else ParameterRequirement.REQUIRED,
                type = argument?.type,
                defaultValue = argument?.defaultValue,
            )
        }.toList()
        val verified = target.patterns.any { it.autoVerify && it.matches(sample(uri)) }
        return DeepLinkVariant(target.componentName, uri, parameters, autoVerify = verified, fromNavigation = true)
    }

    private fun manifestVariants(component: String, pattern: UriPattern): List<DeepLinkVariant> {
        if (pattern.schemes.isEmpty()) return emptyList()
        val hosts: List<String?> = pattern.hosts.ifEmpty { listOf(null) }.toList()
        val paths: List<UriMatcher?> = pattern.paths.ifEmpty { listOf(null) }
        val port = pattern.ports.firstOrNull()?.let { ":$it" }.orEmpty()
        val query = pattern.queries.firstOrNull { it.kind == UriMatcherKind.QUERY_LITERAL }?.let { "?${it.value}" }.orEmpty()
        return pattern.schemes.flatMap { scheme ->
            hosts.flatMap { host ->
                paths.map { path ->
                    val parameters = mutableListOf<VariantParameter>()
                    val authority = when {
                        host == null -> ""
                        // Optional: file:// and some content:// links have no host.
                        host == "*" -> "{host}".also { parameters += VariantParameter("host", ParameterLocation.PATH, ParameterRequirement.OPTIONAL) }
                        host.startsWith("*.") -> "{subdomain}${host.removePrefix("*")}"
                            .also { parameters += VariantParameter("subdomain", ParameterLocation.PATH, ParameterRequirement.REQUIRED) }
                        else -> host
                    }
                    val (pathTemplate, advanced) = pathTemplate(path, parameters)
                    val base = if (host == null) "$scheme:" else "$scheme://$authority$port"
                    DeepLinkVariant(component, base + pathTemplate + query, parameters, pattern.autoVerify, advancedPattern = advanced)
                }
            }
        }
    }

    /** The path part of a template, adding the slots it opens to [parameters]; second: an unrepresentable regex. */
    private fun pathTemplate(matcher: UriMatcher?, parameters: MutableList<VariantParameter>): Pair<String, String?> {
        fun slot(name: String, requirement: ParameterRequirement = ParameterRequirement.REQUIRED): String {
            var unique = name
            var n = 2
            while (parameters.any { it.name == unique }) unique = "$name${n++}"
            parameters += VariantParameter(unique, ParameterLocation.PATH, requirement)
            return "{$unique}"
        }
        return when (matcher?.kind) {
            null -> "" to null
            UriMatcherKind.PATH_LITERAL -> matcher.value to null
            UriMatcherKind.PATH_PREFIX -> matcher.value + slot("rest", ParameterRequirement.OPTIONAL) to null
            UriMatcherKind.PATH_SUFFIX -> slot("path") + matcher.value to null
            UriMatcherKind.PATH_PATTERN -> simplePattern(matcher.value, ::slot) to null
            UriMatcherKind.PATH_ADVANCED_PATTERN -> slot("path") to matcher.value
            else -> "" to null
        }
    }

    /** `PatternMatcher.PATTERN_SIMPLE_GLOB`: `.` any char, `x*` repeats, `\` escapes; each open run becomes a slot. */
    private fun simplePattern(value: String, slot: (String) -> String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length -> { out.append(value[i + 1]); i += 2 }
                c == '.' -> {
                    i += if (i + 1 < value.length && value[i + 1] == '*') 2 else 1
                    val segment = out.toString().trimEnd('/').substringAfterLast('/')
                    val name = segment.takeIf { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' } }
                        ?.replace('-', '_')?.takeIf { it.first().isLetter() } ?: "path"
                    out.append(slot(name))
                }
                c == '*' -> i++ // a literal repeated zero or more times: keep the single literal already written
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun DeepLinkVariant.covers(other: DeepLinkVariant): Boolean {
        val regex = Regex(
            placeholder.split(template.substringBefore('?')).joinToString("[^/?#]+") { Regex.escape(it) },
        )
        return regex.matches(sample(other.template).substringBefore('?'))
    }

    private fun sample(template: String): String = placeholder.replace(template, "x")

    private fun location(uri: String, name: String): ParameterLocation {
        val at = uri.indexOf("{$name}")
        val queryAt = uri.indexOf('?')
        val fragmentAt = uri.indexOf('#')
        return when {
            fragmentAt in 0 until at -> ParameterLocation.FRAGMENT
            queryAt in 0 until at -> ParameterLocation.QUERY
            else -> ParameterLocation.PATH
        }
    }

    private fun encodeQuery(value: String): String = encode(value, keep = "").replace("%20", "+")

    private fun encode(value: String, keep: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = (byte.toInt() and 0xFF).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-._~" || c in keep) append(c)
            else append('%').append("0123456789ABCDEF"[c.code shr 4]).append("0123456789ABCDEF"[c.code and 15])
        }
    }
}

/** What was last typed for one deep link: its parameters' values and the extra query parameters added. */
data class DeepLinkLaunchValues(
    val parameters: Map<String, String> = emptyMap(),
    val extras: List<Pair<String, String>> = emptyList(),
)

/** Remembers [DeepLinkLaunchValues] per app and link template across IDE sessions. */
interface DeepLinkLaunchMemory {
    fun load(packageName: String, template: String): DeepLinkLaunchValues?

    fun save(packageName: String, template: String, values: DeepLinkLaunchValues)
}
