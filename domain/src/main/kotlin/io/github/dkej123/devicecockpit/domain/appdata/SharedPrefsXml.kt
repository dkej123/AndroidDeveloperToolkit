package io.github.dkej123.devicecockpit.domain.appdata

/** The value types `SharedPreferences` can hold, as written by Android's `XmlUtils`. */
enum class PrefType(val label: String) {
    Text("String"),
    Int("Int"),
    Long("Long"),
    Float("Float"),
    Boolean("Boolean"),
    StringSet("String set"),
}

sealed interface PrefValue {
    /** The editable text form: sets are one element per line. */
    val display: String
    val type: PrefType?

    data class Text(val value: String) : PrefValue {
        override val display get() = value
        override val type get() = PrefType.Text
    }

    data class IntValue(val value: kotlin.Int) : PrefValue {
        override val display get() = value.toString()
        override val type get() = PrefType.Int
    }

    data class LongValue(val value: kotlin.Long) : PrefValue {
        override val display get() = value.toString()
        override val type get() = PrefType.Long
    }

    data class FloatValue(val value: kotlin.Float) : PrefValue {
        override val display get() = value.toString()
        override val type get() = PrefType.Float
    }

    data class BooleanValue(val value: kotlin.Boolean) : PrefValue {
        override val display get() = value.toString()
        override val type get() = PrefType.Boolean
    }

    data class StringSet(val values: List<String>) : PrefValue {
        override val display get() = values.joinToString("\n")
        override val type get() = PrefType.StringSet
    }

    /** A key explicitly stored as `null` (`<null name="…"/>`). */
    data object Null : PrefValue {
        override val display get() = "null"
        override val type: PrefType? get() = null
    }

    companion object {
        /** Parses what the user typed for [type], or `null` when it is not a valid value of that type. */
        fun fromInput(type: PrefType, input: String): PrefValue? = when (type) {
            PrefType.Text -> Text(input)
            PrefType.Int -> input.trim().toIntOrNull()?.let(::IntValue)
            PrefType.Long -> input.trim().toLongOrNull()?.let(::LongValue)
            PrefType.Float -> input.trim().toFloatOrNull()?.let(::FloatValue)
            PrefType.Boolean -> when (input.trim().lowercase()) {
                "true" -> BooleanValue(true)
                "false" -> BooleanValue(false)
                else -> null
            }
            PrefType.StringSet -> StringSet(input.split('\n').filter { it.isNotEmpty() })
        }
    }
}

data class PrefEntry(val key: String, val value: PrefValue)

sealed interface SharedPrefsParse {
    data class Parsed(val entries: List<PrefEntry>) : SharedPrefsParse

    data class Malformed(val reason: String) : SharedPrefsParse
}

/**
 * Reads and writes the XML files `SharedPreferences` stores under `shared_prefs/` — a tiny,
 * fixed dialect (one `<map>` of typed elements), parsed here without a general XML library so the
 * domain stays platform-free. [serialize] writes the same shape Android does, so the app reads the
 * edited file back normally.
 */
object SharedPrefsXml {

    private const val HEADER = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"

    fun parse(xml: String): SharedPrefsParse = try {
        SharedPrefsParse.Parsed(Parser(xml).parseMap())
    } catch (failure: MalformedPrefs) {
        SharedPrefsParse.Malformed(failure.message ?: "malformed shared preferences XML")
    }

    fun serialize(entries: List<PrefEntry>): String = buildString {
        append(HEADER).append('\n')
        if (entries.isEmpty()) {
            append("<map />\n")
            return@buildString
        }
        append("<map>\n")
        entries.forEach { (key, value) ->
            val name = "name=\"${escape(key)}\""
            append("    ")
            when (value) {
                is PrefValue.Text -> append("<string $name>${escape(value.value)}</string>")
                is PrefValue.IntValue -> append("<int $name value=\"${value.value}\" />")
                is PrefValue.LongValue -> append("<long $name value=\"${value.value}\" />")
                is PrefValue.FloatValue -> append("<float $name value=\"${value.value}\" />")
                is PrefValue.BooleanValue -> append("<boolean $name value=\"${value.value}\" />")
                is PrefValue.StringSet -> {
                    append("<set $name>\n")
                    value.values.forEach { append("        <string>${escape(it)}</string>\n") }
                    append("    </set>")
                }
                PrefValue.Null -> append("<null $name />")
            }
            append('\n')
        }
        append("</map>\n")
    }

    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    private class MalformedPrefs(message: String) : Exception(message)

    private sealed interface Token {
        data class Open(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean) : Token
        data class Close(val name: String) : Token
        data class Text(val text: String) : Token
    }

    private class Parser(private val xml: String) {
        private var position = 0
        private var peeked: Token? = null

        fun parseMap(): List<PrefEntry> {
            val open = nextTag() as? Token.Open ?: throw MalformedPrefs("expected <map>")
            if (open.name != "map") throw MalformedPrefs("expected <map>, found <${open.name}>")
            if (open.selfClosing) return emptyList()
            val entries = mutableListOf<PrefEntry>()
            while (true) {
                when (val token = nextTag() ?: throw MalformedPrefs("unterminated <map>")) {
                    is Token.Close -> if (token.name == "map") return entries else throw MalformedPrefs("unexpected </${token.name}>")
                    is Token.Open -> entries += entry(token)
                    is Token.Text -> Unit
                }
            }
        }

        private fun entry(open: Token.Open): PrefEntry {
            val key = open.attributes["name"] ?: throw MalformedPrefs("<${open.name}> without a name")
            fun attr(): String = open.attributes["value"] ?: throw MalformedPrefs("<${open.name} name=\"$key\"> without a value")
            fun number(error: String) = MalformedPrefs("\"$key\": $error")
            val value = when (open.name) {
                "string" -> PrefValue.Text(if (open.selfClosing) "" else textUntilClose("string"))
                "int" -> PrefValue.IntValue(attr().toIntOrNull() ?: throw number("not an int"))
                "long" -> PrefValue.LongValue(attr().toLongOrNull() ?: throw number("not a long"))
                "float" -> PrefValue.FloatValue(attr().toFloatOrNull() ?: throw number("not a float"))
                "boolean" -> PrefValue.BooleanValue(attr().toBooleanStrictOrNull() ?: throw number("not a boolean"))
                "set" -> PrefValue.StringSet(if (open.selfClosing) emptyList() else setItems())
                "null" -> PrefValue.Null
                else -> throw MalformedPrefs("unknown element <${open.name}>")
            }
            if (open.name in setOf("int", "long", "float", "boolean", "null") && !open.selfClosing) skipClose(open.name)
            return PrefEntry(key, value)
        }

        private fun setItems(): List<String> {
            val items = mutableListOf<String>()
            while (true) {
                when (val token = nextTag() ?: throw MalformedPrefs("unterminated <set>")) {
                    is Token.Close -> if (token.name == "set") return items else throw MalformedPrefs("unexpected </${token.name}>")
                    is Token.Open -> {
                        if (token.name != "string") throw MalformedPrefs("<set> may only hold <string>")
                        items += if (token.selfClosing) "" else textUntilClose("string")
                    }
                    is Token.Text -> Unit
                }
            }
        }

        private fun textUntilClose(name: String): String {
            val text = StringBuilder()
            while (true) {
                when (val token = next() ?: throw MalformedPrefs("unterminated <$name>")) {
                    is Token.Text -> text.append(token.text)
                    is Token.Close -> if (token.name == name) return text.toString() else throw MalformedPrefs("unexpected </${token.name}>")
                    is Token.Open -> throw MalformedPrefs("unexpected <${token.name}> inside <$name>")
                }
            }
        }

        private fun skipClose(name: String) {
            while (true) {
                when (val token = next() ?: throw MalformedPrefs("unterminated <$name>")) {
                    is Token.Close -> if (token.name == name) return
                    is Token.Text -> if (token.text.isNotBlank()) throw MalformedPrefs("text inside <$name>")
                    is Token.Open -> throw MalformedPrefs("unexpected <${token.name}> inside <$name>")
                }
            }
        }

        /** The next element token, skipping whitespace-only text between elements. */
        private fun nextTag(): Token? {
            while (true) {
                val token = next() ?: return null
                if (token is Token.Text && token.text.isBlank()) continue
                if (token is Token.Text) throw MalformedPrefs("unexpected text \"${token.text.take(20)}\"")
                return token
            }
        }

        private fun next(): Token? {
            peeked?.let { peeked = null; return it }
            while (position < xml.length) {
                if (xml.startsWith("<?", position)) {
                    position = end("?>")
                    continue
                }
                if (xml.startsWith("<!--", position)) {
                    position = end("-->")
                    continue
                }
                if (xml[position] == '<') return tag()
                val stop = xml.indexOf('<', position).let { if (it < 0) xml.length else it }
                val text = decode(xml.substring(position, stop))
                position = stop
                return Token.Text(text)
            }
            return null
        }

        private fun end(marker: String): Int {
            val index = xml.indexOf(marker, position)
            if (index < 0) throw MalformedPrefs("unterminated markup")
            return index + marker.length
        }

        private fun tag(): Token {
            val close = xml.indexOf('>', position)
            if (close < 0) throw MalformedPrefs("unterminated tag")
            var body = xml.substring(position + 1, close).trim()
            position = close + 1
            if (body.startsWith("/")) return Token.Close(body.removePrefix("/").trim())
            val selfClosing = body.endsWith("/")
            if (selfClosing) body = body.removeSuffix("/").trim()
            val name = body.takeWhile { !it.isWhitespace() }
            if (name.isEmpty()) throw MalformedPrefs("empty tag")
            val attributes = ATTRIBUTE.findAll(body.substring(name.length)).associate { match ->
                match.groupValues[1] to decode(match.groupValues[2].ifEmpty { match.groupValues[3] })
            }
            return Token.Open(name, attributes, selfClosing)
        }

        private fun decode(text: String): String = ENTITY.replace(text) { match ->
            val entity = match.groupValues[1]
            when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)?.let(::codePoint) ?: match.value
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint) ?: match.value
                else -> match.value
            }
        }

        private fun codePoint(value: kotlin.Int): String = buildString { appendCodePoint(value) }

        private fun StringBuilder.appendCodePoint(value: kotlin.Int) {
            if (value < 0x10000) {
                append(value.toChar())
            } else {
                val offset = value - 0x10000
                append((0xD800 + (offset shr 10)).toChar())
                append((0xDC00 + (offset and 0x3FF)).toChar())
            }
        }

        companion object {
            private val ATTRIBUTE = Regex("""([A-Za-z_][\w.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
            private val ENTITY = Regex("""&(#x[0-9A-Fa-f]+|#\d+|[a-z]+);""")
        }
    }
}
