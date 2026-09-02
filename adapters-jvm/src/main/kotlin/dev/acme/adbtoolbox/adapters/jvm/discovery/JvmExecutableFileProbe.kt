package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.ExecutableFileProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Checks a resolved candidate path against the real filesystem: it must exist, be a regular file
 * (not a directory), and be executable. Runs on [Dispatchers.IO] so callers never block on
 * filesystem I/O from the EDT. */
class JvmExecutableFileProbe : ExecutableFileProbe {
    override suspend fun isExecutable(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        file.isFile && file.canExecute()
    }
}
