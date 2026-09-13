package dev.acme.adbtoolbox.domain.logcat

private val WHITESPACE = Regex("\\s+")

/**
 * Parses `pidof <pkg>` stdout (task 035). Blank output means the package has no running process;
 * any token that isn't a plain integer makes the whole result [LogcatPidResolution.Malformed]
 * rather than silently discarding the bad token and keeping the rest.
 */
object LogcatPidParser {

    fun parse(stdout: String): LogcatPidResolution {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) return LogcatPidResolution.NoProcess

        val tokens = trimmed.split(WHITESPACE)
        val pids = mutableSetOf<Int>()
        for (token in tokens) {
            val pid = token.toIntOrNull() ?: return LogcatPidResolution.Malformed(stdout)
            pids += pid
        }
        return LogcatPidResolution.Resolved(pids.map(::ProcessId).toSet())
    }
}
