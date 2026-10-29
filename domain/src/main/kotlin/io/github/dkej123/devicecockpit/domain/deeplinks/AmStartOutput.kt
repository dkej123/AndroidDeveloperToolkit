package io.github.dkej123.devicecockpit.domain.deeplinks

/**
 * What `am start -W` reports about a launch (ActivityManagerShellCommand): which activity took the
 * intent, whether the system chooser was shown instead, and whether an already-running top activity
 * received it through onNewIntent rather than a new launch.
 */
data class AmStartOutput(
    val ok: Boolean,
    val error: String?,
    val unresolved: Boolean,
    val activity: String?,
    val deliveredToRunningInstance: Boolean,
    /** COLD / WARM / HOT, or UNKNOWN when nothing new was started. */
    val launchState: String? = null,
    val totalTimeMs: Long? = null,
) {
    val chooserShown: Boolean get() = activity != null && isChooser(activity)
    val handlerPackage: String? get() = activity?.takeUnless { chooserShown }?.substringBefore('/')

    companion object {
        private val chooserActivities = listOf("ResolverActivity", "ChooserActivity")

        fun isChooser(component: String): Boolean = chooserActivities.any { component.endsWith(it) }

        private fun value(lines: List<String>, key: String) =
            lines.firstOrNull { it.startsWith("$key:", true) }?.substringAfter(':')?.trim()

        fun parse(output: String): AmStartOutput {
            val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val unresolved = lines.any { it.contains("unable to resolve Intent", true) || it.contains("No Activity found", true) }
            val error = lines.firstOrNull {
                it.startsWith("Error:", true) || (it.startsWith("Status:", true) && !it.substringAfter(':').trim().equals("ok", true))
            }
            return AmStartOutput(
                ok = error == null && !unresolved,
                error = error,
                unresolved = unresolved,
                activity = value(lines, "Activity")?.ifEmpty { null },
                deliveredToRunningInstance = lines.any { it.startsWith("Warning:", true) && it.contains("delivered to currently running", true) },
                launchState = value(lines, "LaunchState")?.substringBefore(' ')?.ifEmpty { null },
                totalTimeMs = value(lines, "TotalTime")?.toLongOrNull(),
            )
        }
    }
}
