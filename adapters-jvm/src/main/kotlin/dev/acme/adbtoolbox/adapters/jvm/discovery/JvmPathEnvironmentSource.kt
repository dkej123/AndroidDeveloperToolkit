package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.PathEnvironmentSource
import java.io.File

/** Splits a raw `PATH`-style variable on [separator], dropping blank segments (e.g. from a leading,
 * trailing, or doubled separator). Pure so every OS's separator convention is unit-testable without
 * touching a real environment variable. */
fun splitPathVariable(rawPath: String?, separator: Char): List<String> =
    rawPath?.split(separator)?.filter { it.isNotBlank() } ?: emptyList()

/** Reads the real `PATH` environment variable. The approved last-resort discovery tier
 * (design/IMPLEMENTATION.md §4) — isolated here per ADR 0002. */
class JvmPathEnvironmentSource(
    private val pathVariableProvider: () -> String? = { System.getenv("PATH") },
    private val pathSeparator: Char = File.pathSeparatorChar,
) : PathEnvironmentSource {
    override suspend fun directories(): List<String> = splitPathVariable(pathVariableProvider(), pathSeparator)
}
