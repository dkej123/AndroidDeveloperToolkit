package dev.acme.adbtoolbox.domain.discovery

/** A deterministic [ExecutableFileProbe] test double: a path is "executable" only once
 * [markExecutable] has been called for it — every other path validates as `false`, including
 * paths that were previously marked and then [unmark]ed (simulating a moved/deleted file). */
class FakeExecutableFileProbe : ExecutableFileProbe {
    private val executablePaths = mutableSetOf<String>()

    override suspend fun isExecutable(path: String): Boolean = path in executablePaths

    fun markExecutable(path: String) {
        executablePaths += path
    }

    fun unmark(path: String) {
        executablePaths -= path
    }
}
