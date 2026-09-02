package dev.acme.adbtoolbox.domain.process

/**
 * A binary-safe destination for raw process output bytes (e.g. a screenshot capture). Kept free of
 * `java.io` types so it stays usable from KMP-ready callers; JVM callers back it with a file or
 * in-memory stream.
 */
fun interface ByteSink {
    fun write(bytes: ByteArray)
}
