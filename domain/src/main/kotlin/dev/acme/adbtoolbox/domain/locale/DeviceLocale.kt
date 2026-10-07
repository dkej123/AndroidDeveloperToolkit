package dev.acme.adbtoolbox.domain.locale

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken

/** A BCP-47 language tag such as `pl-PL`, `ar-EG` or the pseudo-locale `en-XA`; [of] validates it. */
@JvmInline
value class LocaleTag private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        // language (2–3 letters), optional script (4), optional region (2 letters / 3 digits).
        private val TAG = Regex("""[a-zA-Z]{2,3}(-[a-zA-Z]{4})?(-([a-zA-Z]{2}|\d{3}))?""")

        fun of(text: String): LocaleTag? {
            val trimmed = text.trim().replace('_', '-')
            if (!TAG.matches(trimmed)) return null
            val parts = trimmed.split('-')
            val normalized = parts.mapIndexed { index, part ->
                when {
                    index == 0 -> part.lowercase()
                    part.length == 4 -> part.lowercase().replaceFirstChar(Char::uppercase)
                    else -> part.uppercase()
                }
            }
            return LocaleTag(normalized.joinToString("-"))
        }
    }
}

/** A locale offered as a quick pick: [rightToLeft] and [pseudo] mark the ones that test layouts. */
data class CatalogLocale(val tag: LocaleTag, val title: String, val rightToLeft: Boolean = false, val pseudo: Boolean = false)

/**
 * The quick picks of the language list: common languages, RTL ones and Android's pseudo-locales
 * (`en-XA` accents and lengthens every string, `ar-XB` mirrors the layout), which exist on every
 * device but must be enabled in Developer options to appear in Settings.
 *
 * Partly from Oh My Android, MIT — `Sources/Features/DisplayFeatures.swift` (`DeviceLanguageFeature`).
 */
object LocaleCatalog {
    val entries: List<CatalogLocale> = listOf(
        catalog("en-US", "English (United States)"),
        catalog("en-GB", "English (United Kingdom)"),
        catalog("pl-PL", "Polski"),
        catalog("de-DE", "Deutsch"),
        catalog("fr-FR", "Français"),
        catalog("es-ES", "Español"),
        catalog("it-IT", "Italiano"),
        catalog("pt-BR", "Português (Brasil)"),
        catalog("sv-SE", "Svenska"),
        catalog("uk-UA", "Українська"),
        catalog("ja-JP", "日本語"),
        catalog("zh-CN", "中文 (简体)"),
        catalog("ko-KR", "한국어"),
        catalog("hi-IN", "हिन्दी"),
        catalog("ar-EG", "العربية", rightToLeft = true),
        catalog("he-IL", "עברית", rightToLeft = true),
        catalog("fa-IR", "فارسی", rightToLeft = true),
        catalog("en-XA", "Pseudo-locale: accented, longer text", pseudo = true),
        catalog("ar-XB", "Pseudo-locale: right-to-left, mirrored", rightToLeft = true, pseudo = true),
    )

    private fun catalog(tag: String, title: String, rightToLeft: Boolean = false, pseudo: Boolean = false) =
        CatalogLocale(requireNotNull(LocaleTag.of(tag)), title, rightToLeft, pseudo)
}

/** What the locale helper is asked to do; every action reports the device's locales afterwards. */
sealed interface LocaleAction {
    data object Read : LocaleAction

    data class Set(val locales: List<LocaleTag>) : LocaleAction
}

/** The device's locale list (first = the UI language), or why it could not be read or changed. */
sealed interface LocaleRead {
    data class Locales(val tags: List<String>) : LocaleRead

    data class Failed(val reason: String) : LocaleRead
}

/** Reads and changes the device locales through the pushed device helper's `LocaleMain` (task 060). */
interface DeviceLocalePort {
    suspend fun run(serial: DeviceSerial, action: LocaleAction): LocaleRead
}

/**
 * Command/parser for the helper's `LocaleMain` entry point: it prints [HEADER], then
 * `locales=<comma-separated tags>` or `error=<exception>`, then `END`.
 */
object LocaleHelperCommand {
    private const val HEADER = "ADBTOOLBOX-LOCALE 1"
    private const val MAIN_CLASS = "dev.acme.adbtoolbox.devicehelper.LocaleMain"

    /** [remotePath] is the pushed helper jar, safe as a literal `CLASSPATH=`; tags are validated [LocaleTag]s. */
    fun request(serial: DeviceSerial, remotePath: String, action: LocaleAction): AdbDeviceRequest {
        val arguments = when (action) {
            LocaleAction.Read -> listOf("get")
            is LocaleAction.Set -> listOf("set", action.locales.joinToString(",") { it.value })
        }
        val literals = listOf("CLASSPATH=$remotePath", "app_process", "/", MAIN_CLASS) + arguments
        return AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(*literals.map { ShellToken.Literal(it) }.toTypedArray())))
    }

    fun parse(result: AdbTextResult): LocaleRead {
        if (result.outcome !is AdbOutcome.Completed) return LocaleRead.Failed("Could not reach the device")
        val lines = result.stdout.lines().map(String::trim)
        if (HEADER !in lines) return LocaleRead.Failed("Device helper did not start")
        lines.firstOrNull { it.startsWith("error=") }?.let { return LocaleRead.Failed(it.removePrefix("error=")) }
        val tags = lines.firstOrNull { it.startsWith("locales=") }?.removePrefix("locales=")
            ?: return LocaleRead.Failed("Device helper reported no locales")
        return LocaleRead.Locales(tags.split(',').map(String::trim).filter(String::isNotEmpty))
    }
}
