package dev.acme.adbtoolbox.domain.settings

/** A deterministic [DirectoryProbe] test double: a path is a "valid directory" only once
 * [markValidDirectory] has been called for it. */
class FakeDirectoryProbe : DirectoryProbe {
    private val validDirectories = mutableSetOf<String>()

    override suspend fun isValidDirectory(path: String): Boolean = path in validDirectories

    fun markValidDirectory(path: String) {
        validDirectories += path
    }

    fun unmark(path: String) {
        validDirectories -= path
    }
}
