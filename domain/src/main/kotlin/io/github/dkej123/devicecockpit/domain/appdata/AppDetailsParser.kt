package io.github.dkej123.devicecockpit.domain.appdata

enum class PermissionKind { RUNTIME, INSTALL, SIGNATURE, UNKNOWN }

enum class PermissionState {
    NOT_REQUESTED, GRANTED, GRANTED_ONE_TIME, DENIED, DENIED_PERMANENTLY, FIXED_READ_ONLY, UNKNOWN,
}

enum class PermissionFlag { USER_SET, USER_FIXED, ONE_TIME, POLICY_FIXED, SYSTEM_FIXED }

data class AppPermission(
    val name: String,
    val kind: PermissionKind,
    val state: PermissionState,
    val flags: Set<PermissionFlag> = emptySet(),
) {
    val granted: Boolean? get() = when (state) {
        PermissionState.GRANTED, PermissionState.GRANTED_ONE_TIME -> true
        PermissionState.DENIED, PermissionState.DENIED_PERMANENTLY, PermissionState.FIXED_READ_ONLY -> false
        PermissionState.NOT_REQUESTED, PermissionState.UNKNOWN -> null
    }
    val runtime: Boolean get() = kind == PermissionKind.RUNTIME
    val mutable: Boolean get() = kind == PermissionKind.RUNTIME &&
        PermissionFlag.POLICY_FIXED !in flags && PermissionFlag.SYSTEM_FIXED !in flags
}

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
    private val GRANT = Regex("""^\s*([\w.]+): granted=(true|false)(?:,\s*flags=\[\s*([^]]*)])?.*""")

    fun parse(
        packageName: String,
        dump: String,
        androidUserId: Int = 0,
        dangerousPermissions: Set<String> = emptySet(),
    ): AppDetails {
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
            isStopped = Regex("""^\s*User $androidUserId:.*\bstopped=(true|false)""", RegexOption.MULTILINE)
                .find(section)?.groupValues?.get(1)?.toBooleanStrictOrNull(),
            permissions = permissions(section, androidUserId, dangerousPermissions),
        )
    }

    /** From `Package [<pkg>]` to the next top-level heading, so other packages' lines never leak in. */
    private fun packageSection(packageName: String, dump: String): String {
        val start = dump.indexOf("Package [$packageName]").takeIf { it >= 0 } ?: return dump
        val end = Regex("""^\S""", RegexOption.MULTILINE).find(dump, start + 1)?.range?.first ?: dump.length
        return dump.substring(start, end)
    }

    private fun permissions(section: String, androidUserId: Int, dangerousPermissions: Set<String>): List<AppPermission> {
        val result = linkedMapOf<String, AppPermission>()
        var block: String? = null
        var activeUser = false
        section.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed == "requested permissions:" -> block = "requested"
                trimmed == "install permissions:" -> block = "install"
                trimmed == "runtime permissions:" -> block = if (activeUser) "runtime" else null
                trimmed.endsWith(":") && !trimmed.contains(' ') -> block = null
                trimmed.startsWith("User ") -> {
                    activeUser = trimmed.startsWith("User $androidUserId:")
                    block = null
                }
                trimmed.startsWith("gids=") -> if (block != "runtime") block = null
                block == "requested" && trimmed.isNotEmpty() && ' ' !in trimmed && ':' !in trimmed ->
                    result.putIfAbsent(trimmed, AppPermission(trimmed, PermissionKind.UNKNOWN, PermissionState.NOT_REQUESTED))
                block == "install" || block == "runtime" -> GRANT.find(trimmed)?.let { match ->
                    val name = match.groupValues[1]
                    val granted = match.groupValues[2] == "true"
                    val flags = match.groupValues[3].split(Regex("""[|,\s]+"""))
                        .mapNotNull { value -> PermissionFlag.entries.firstOrNull { it.name == value } }
                        .toSet()
                    val fixed = PermissionFlag.POLICY_FIXED in flags || PermissionFlag.SYSTEM_FIXED in flags
                    val state = when {
                        fixed -> PermissionState.FIXED_READ_ONLY
                        granted && PermissionFlag.ONE_TIME in flags -> PermissionState.GRANTED_ONE_TIME
                        granted -> PermissionState.GRANTED
                        PermissionFlag.USER_FIXED in flags -> PermissionState.DENIED_PERMANENTLY
                        else -> PermissionState.DENIED
                    }
                    result[name] = AppPermission(name, if (block == "runtime") PermissionKind.RUNTIME else PermissionKind.INSTALL, state, flags)
                }
            }
        }
        return result.values.map { permission ->
            if (permission.kind == PermissionKind.UNKNOWN && permission.name in dangerousPermissions) {
                permission.copy(kind = PermissionKind.RUNTIME)
            } else permission
        }.sortedBy { it.name }
    }
}
