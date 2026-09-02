package dev.acme.adbtoolbox.domain.adb

/**
 * The result of a feature-local parser turning [AdbTextResult] stdout into a typed value. Keeps
 * parsing failures (garbled or unexpected device output) a normal, typed outcome rather than a
 * thrown exception, per adb-development's "handle malformed/unexpected output without crashing
 * the caller."
 */
sealed interface AdbParseResult<out T> {
    data class Parsed<out T>(val value: T) : AdbParseResult<T>

    data class Malformed(val raw: String, val reason: String) : AdbParseResult<Nothing>
}
