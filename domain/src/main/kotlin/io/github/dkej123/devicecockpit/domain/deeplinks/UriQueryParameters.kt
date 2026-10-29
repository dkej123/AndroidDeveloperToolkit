package io.github.dkej123.devicecockpit.domain.deeplinks

/** Ordered query pairs, including repeated keys; never treats a fragment as part of the query. */
object UriQueryParameters {
    fun parse(uri: String): List<Pair<String, String>> {
        val head = uri.substringBefore('#')
        if ('?' !in head) return emptyList()
        return head.substringAfter('?').split('&').filter(String::isNotEmpty).map { pair ->
            decode(pair.substringBefore('=')) to decode(if ('=' in pair) pair.substringAfter('=') else "")
        }
    }

    fun replace(uri: String, parameters: List<Pair<String, String>>): String {
        val fragment = if ('#' in uri) "#" + uri.substringAfter('#') else ""
        val base = uri.substringBefore('#').substringBefore('?')
        val query = parameters.filter { it.first.isNotEmpty() || it.second.isNotEmpty() }
            .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return base + if (query.isEmpty()) fragment else "?$query$fragment"
    }

    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = (byte.toInt() and 255).toChar()
            if (c.code < 128 && (c.isLetterOrDigit() || c in "-._~")) append(c)
            else append('%').append("0123456789ABCDEF"[c.code shr 4]).append("0123456789ABCDEF"[c.code and 15])
        }
    }

    private fun decode(value: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            val hex = if (value[i] == '%' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) { bytes += hex.toByte(); i += 3 }
            else {
                val end = if (value[i].isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) i + 2 else i + 1
                bytes += (if (value[i] == '+') " " else value.substring(i, end)).encodeToByteArray().toList()
                i = end
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
