package io.github.dkej123.devicecockpit.domain.deeplinks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface AssetLinksResult {
    data class Valid(val rules: List<DynamicRule>) : AssetLinksResult
    data class Invalid(val reason: String) : AssetLinksResult
}

object AssetLinksParser {
    private const val RELATION = "delegate_permission/common.handle_all_urls"

    fun parse(json: String, packageName: String, certificatesSha256: Set<String>): AssetLinksResult {
        val statements = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull()
            ?: return AssetLinksResult.Invalid("assetlinks.json is not a JSON array")
        val normalizedCertificates = certificatesSha256.map(::normalizeCertificate).toSet()
        val statement = statements.mapNotNull { it as? JsonObject }.firstOrNull { item ->
            val relations = (item["relation"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val target = item["target"] as? JsonObject
            val remoteCerts = (target?.get("sha256_cert_fingerprints") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.map(::normalizeCertificate).orEmpty()
            RELATION in relations && target?.get("namespace")?.jsonPrimitive?.contentOrNull == "android_app" &&
                target["package_name"]?.jsonPrimitive?.contentOrNull == packageName && remoteCerts.any { it in normalizedCertificates }
        } ?: return AssetLinksResult.Invalid("no matching handle_all_urls statement for package and certificate")
        val extension = (statement["relation_extensions"] as? JsonObject)?.get(RELATION) as? JsonObject
        val components = extension?.get("dynamic_app_link_components") as? JsonArray ?: return AssetLinksResult.Valid(emptyList())
        val rules = components.mapNotNull { element ->
            val rule = element as? JsonObject ?: return@mapNotNull null
            DynamicRule(
                exclude = rule["exclude"]?.jsonPrimitive?.booleanOrNull == true,
                path = rule["/"]?.jsonPrimitive?.contentOrNull,
                fragment = rule["#"]?.jsonPrimitive?.contentOrNull,
                query = (rule["?"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            )
        }
        return AssetLinksResult.Valid(rules)
    }

    private fun normalizeCertificate(value: String) = value.filter(Char::isLetterOrDigit).uppercase()
}

fun AssetLinksResult.Valid.allows(path: String, query: String, fragment: String): Boolean {
    if (rules.isEmpty()) return true
    val queryValues = query.split('&').filter(String::isNotEmpty).associate { part ->
        part.substringBefore('=') to part.substringAfter('=', "")
    }
    val matched = rules.firstOrNull { rule ->
        glob(rule.path, path) && glob(rule.fragment, fragment) && rule.query.all { (key, expected) ->
            queryValues[key]?.let { glob(expected, it) } == true
        }
    } ?: return false
    return !matched.exclude
}

fun AppLinkVerification.allows(uri: String): Boolean {
    if (remoteValid != true || dynamicRules.isEmpty()) return true
    val parsed = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]+([^?#]*)(?:\?([^#]*))?(?:#(.*))?$""").matchEntire(uri)
        ?: return false
    return AssetLinksResult.Valid(dynamicRules).allows(parsed.groupValues[1], parsed.groupValues[2], parsed.groupValues[3])
}

private fun glob(pattern: String?, value: String): Boolean {
    if (pattern == null) return true
    val regex = buildString {
        append('^')
        pattern.forEach { character -> when (character) {
            '*' -> append(".*")
            '?' -> append('.')
            else -> append(Regex.escape(character.toString()))
        } }
        append('$')
    }
    return Regex(regex).matches(value)
}
