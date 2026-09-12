package dev.acme.adbtoolbox.domain.display.fontscale

/** The result of parsing `settings get system font_scale` stdout. */
sealed interface FontScaleReadResult {
    data class Value(val value: Double) : FontScaleReadResult
    data class Malformed(val raw: String) : FontScaleReadResult
}

/**
 * Renders [value] for both the `settings put` command line and display text, always with `.` as the
 * decimal separator regardless of the JVM's default locale. `Double.toString()` is defined by the
 * Java/Kotlin language spec to always use `.` and the shortest round-tripping decimal representation
 * — it is not affected by [java.util.Locale.getDefault], unlike `String.format`/`NumberFormat`, which
 * this function deliberately avoids.
 */
fun formatFontScale(value: Double): String = value.toString()

/**
 * Parses `settings get system font_scale` stdout into a [FontScaleReadResult]. `"null"` (Android's
 * `settings get` output when the key was never set) and blank output both mean the platform default.
 * Uses [String.toDoubleOrNull], which — like [formatFontScale] — is locale-independent: it requires
 * `.` as the decimal separator no matter the JVM's default locale, so a device or JVM in a
 * comma-decimal locale can never corrupt the parsed value.
 */
fun parseFontScale(raw: String): FontScaleReadResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
        return FontScaleReadResult.Value(FontScalePresets.DEFAULT)
    }
    val parsed = trimmed.toDoubleOrNull() ?: return FontScaleReadResult.Malformed(raw)
    return FontScaleReadResult.Value(parsed)
}
