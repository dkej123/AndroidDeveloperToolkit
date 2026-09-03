package dev.acme.adbtoolbox.domain.logcat

/**
 * A logcat record's priority level, matching every level `logcat -v threadtime` can emit
 * (`android.util.Log`'s VERBOSE..ASSERT). The wire format uses a single letter per level; historic
 * `liblog` output shows `F` (Fatal) for the same priority `android.util.Log` calls ASSERT, so both
 * letters map to [ASSERT] — see [fromLetter].
 */
enum class LogSeverity {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ASSERT;

    companion object {
        fun fromLetter(letter: Char): LogSeverity? = when (letter) {
            'V' -> VERBOSE
            'D' -> DEBUG
            'I' -> INFO
            'W' -> WARN
            'E' -> ERROR
            'F', 'A' -> ASSERT
            else -> null
        }
    }
}
