package dev.acme.adbtoolbox.e2e.infra

import java.io.File

/**
 * Reads the plugin's own diagnostics log (docs/diagnostics.md) between two points in a test, so a
 * test can assert on what the plugin *did* — which adb commands ran, how long they took, whether
 * anything was logged at ERROR — without instrumenting production code.
 */
class PluginLog(private val file: File = E2eConfig.pluginLog) {
    data class Mark(internal val offset: Long)

    /**
     * One finished device/adb call: a `[process] finished` line (binary adb) or an `[adb] done|failed`
     * line (any transport; successful ddmlib calls are only logged with Verbose diagnostics on).
     */
    data class Command(val source: String, val command: String, val outcome: String, val millis: Long, val line: String)

    fun mark(): Mark = Mark(if (file.exists()) file.length() else 0L)

    fun linesSince(mark: Mark): List<String> {
        if (!file.exists()) return emptyList()
        file.inputStream().use { input ->
            val length = file.length()
            val start = if (mark.offset > length) 0L else mark.offset // log rotated
            input.skip(start)
            return input.readBytes().decodeToString().lines().filter { it.isNotBlank() }
        }
    }

    fun errorsSince(mark: Mark): List<String> = linesSince(mark).filter { " ERROR " in it }

    fun commandsSince(mark: Mark): List<Command> = linesSince(mark).mapNotNull(::parseCommand)

    /** Waits until a line matching [predicate] appears after [mark]; returns it. */
    fun awaitLine(mark: Mark, timeoutMillis: Long, predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            linesSince(mark).firstOrNull(predicate)?.let { return it }
            Thread.sleep(250)
        }
        error("no matching plugin-log line within ${timeoutMillis}ms; last lines:\n" + linesSince(mark).takeLast(20).joinToString("\n"))
    }

    private fun parseCommand(line: String): Command? {
        val source = when {
            "[process] finished" in line -> "process"
            "[adb] done" in line || "[adb] failed" in line -> TRANSPORT.find(line)?.groupValues?.get(1) ?: "adb"
            else -> return null
        }
        val command = (COMMAND.find(line) ?: REQUEST.find(line))?.groupValues?.get(1) ?: return null
        val outcome = OUTCOME.find(line)?.groupValues?.get(1) ?: ""
        val millis = MILLIS.find(line)?.groupValues?.get(1)?.toLong() ?: -1
        return Command(source, command, outcome, millis, line)
    }

    private companion object {
        val COMMAND = Regex("""command="([^"]*)"""")
        val REQUEST = Regex("""request="([^"]*)"""")
        val TRANSPORT = Regex("""transport=(\w+)""")
        val OUTCOME = Regex("""outcome="?([^" ]*)""")
        val MILLIS = Regex(""" ms=(\d+)""")
    }
}
