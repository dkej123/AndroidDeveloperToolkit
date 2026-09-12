package dev.acme.adbtoolbox.domain.packages

/**
 * One row of a discovered package list. [label] is always populated — either the resolved
 * `dumpsys package` label ([labelResolved] `true`) or [packageName] itself as the fallback
 * ([labelResolved] `false`), per ADR 0007: a package is never dropped or shown blank because its
 * label could not be parsed. [isDebuggable] is `null` while metadata enrichment for this package
 * has not completed yet, or could not be determined.
 */
data class PackageEntry(
    val packageName: String,
    val label: String,
    val labelResolved: Boolean,
    val isDebuggable: Boolean?,
) {
    companion object {
        /** The initial row for a freshly parsed package name, before metadata enrichment runs. */
        fun unresolved(packageName: String): PackageEntry = PackageEntry(
            packageName = packageName,
            label = packageName,
            labelResolved = false,
            isDebuggable = null,
        )
    }
}
