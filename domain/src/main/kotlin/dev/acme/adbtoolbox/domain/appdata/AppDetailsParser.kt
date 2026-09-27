package dev.acme.adbtoolbox.domain.appdata

data class AppPermission(val name: String, val granted: Boolean?, val runtime: Boolean)

/** What `dumpsys package <pkg>` says about one app. Fields the dump does not contain are `null`. */
data class AppDetails(
    val packageName: String,
    val versionName: String?,
    val versionCode: String?,
    val minSdk: String?,
    val targetSdk: String?,
    val uid: String?,
    val codePath: String?,
    val dataDir: String?,
    val primaryAbi: String?,
    val firstInstallTime: String?,
    val lastUpdateTime: String?,
    val installer: String?,
    val flags: List<String>,
    val isStopped: Boolean?,
    val permissions: List<AppPermission>,
) {
    val isDebuggable: Boolean get() = "DEBUGGABLE" in flags
}

/** Parses the app's own section of `dumpsys package <pkg>` into [AppDetails]. */
object AppDetailsParser {

    private val FLAGS = Regex("""^\s*flags=\[(.*?)]""", RegexOption.MULTILINE)
    private val VERSION_LINE = Regex("""^\s*versionCode=(\S+)(?:\s+minSdk=(\S+))?(?:\s+targetSdk=(\S+))?""", RegexOption.MULTILINE)
    private val STOPPED = Regex("""^\s*User 0:.*\bstopped=(true|false)""", RegexOption.MULTILINE)
    private val GRANT = Regex("""^\s*([\w.]+): granted=(true|false)""")

    fun parse(packageName: String, dump: String): AppDetails {
        val section = packageSection(packageName, dump)
        fun field(name: String): String? =
            Regex("""^\s*$name=(.*)$""", RegexOption.MULTILINE).find(section)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() && it != "null" }
        val version = VERSION_LINE.find(section)
        return AppDetails(
            packageName = packageName,
            versionName = field("versionName"),
            versionCode = version?.groupValues?.get(1),
            minSdk = version?.groupValues?.get(2)?.ifEmpty { null },
            targetSdk = version?.groupValues?.get(3)?.ifEmpty { null },
            uid = field("userId"),
            codePath = field("codePath"),
            dataDir = field("dataDir"),
            primaryAbi = field("primaryCpuAbi"),
            firstInstallTime = field("firstInstallTime"),
            lastUpdateTime = field("lastUpdateTime"),
            installer = field("installerPackageName"),
            flags = FLAGS.find(section)?.groupValues?.get(1)?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
            isStopped = STOPPED.find(section)?.groupValues?.get(1)?.toBooleanStrictOrNull(),
            permissions = permissions(section),
        )
    }

    /** From `Package [<pkg>]` to the next top-level heading, so other packages' lines never leak in. */
    private fun packageSection(packageName: String, dump: String): String {
        val start = dump.indexOf("Package [$packageName]").takeIf { it >= 0 } ?: return dump
        val end = Regex("""^\S""", RegexOption.MULTILINE).find(dump, start + 1)?.range?.first ?: dump.length
        return dump.substring(start, end)
    }

    private fun permissions(section: String): List<AppPermission> {
        val result = linkedMapOf<String, AppPermission>()
        var block: String? = null
        section.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed == "requested permissions:" -> block = "requested"
                trimmed == "install permissions:" -> block = "install"
                trimmed == "runtime permissions:" -> block = "runtime"
                trimmed.endsWith(":") && !trimmed.contains(' ') -> block = null
                trimmed.startsWith("User ") || trimmed.startsWith("gids=") -> if (block != "runtime") block = null
                block == "requested" && trimmed.isNotEmpty() && ' ' !in trimmed && ':' !in trimmed ->
                    result.putIfAbsent(trimmed, AppPermission(trimmed, granted = null, runtime = false))
                block == "install" || block == "runtime" -> GRANT.find(trimmed)?.let { match ->
                    val name = match.groupValues[1]
                    result[name] = AppPermission(name, match.groupValues[2] == "true", runtime = block == "runtime")
                }
            }
        }
        return result.values.sortedBy { it.name }
    }
}
