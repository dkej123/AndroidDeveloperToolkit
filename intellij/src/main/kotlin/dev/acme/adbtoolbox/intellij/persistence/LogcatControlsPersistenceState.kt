package dev.acme.adbtoolbox.intellij.persistence

/**
 * The Logcat-controls slice of [AdbToolboxProjectState] (task 037, `design/README.md`'s state
 * model: `logMinLevel`, `logPackageFilterOn`, `logWrap`). A plain mutable bean (public `var`s,
 * no-arg constructor) as IntelliJ's `XmlSerializer` requires, mirroring [NetworkState]'s shape.
 * Named with the `Persistence` suffix (unlike [NetworkState]) specifically to avoid colliding with
 * [dev.acme.adbtoolbox.application.logcat.LogcatControlsState] — the two are never meant to be
 * confused for one another.
 * [minSeverityName] stores [dev.acme.adbtoolbox.domain.logcat.LogSeverity.name] (e.g. `"ERROR"`),
 * or `null` for "no floor" — the enum's own name round-trips exactly, unlike its wire letter (`F`
 * and `A` both parse to [dev.acme.adbtoolbox.domain.logcat.LogSeverity.ASSERT]). A flat nullable
 * `String` is everything `XmlSerializer` needs, so no nested enum bean is introduced for one
 * already-primitive field.
 */
class LogcatControlsPersistenceState {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var minSeverityName: String? = null
    var packageFilterOn: Boolean = true
    var wrap: Boolean = false

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
