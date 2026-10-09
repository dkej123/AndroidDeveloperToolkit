package io.github.dkej123.devicecockpit.adapters.jvm.settings

import io.github.dkej123.devicecockpit.domain.settings.DirectoryProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Checks a candidate capture-directory path against the real filesystem: it must exist and be a
 * directory. Runs on [Dispatchers.IO] so callers never block on filesystem I/O from the EDT. */
class JvmDirectoryProbe : DirectoryProbe {
    override suspend fun isValidDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).isDirectory
    }
}
