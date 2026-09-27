package dev.acme.adbtoolbox.domain.nav

/**
 * The closed set of destinations on the rail (`design/README.md` §2): four feature views plus
 * Settings pinned to the rail bottom.
 *
 * There is deliberately no Display view: its font scale, display scale and quick toggles live in
 * the Device view (user decision, 2026-09-25). Do not re-add a Display destination. [routeKey] is the stable identifier persisted (ADR 0006) and
 * passed to `:intellij`'s `FeatureViewHost.registerFeatureView`/`show` (task 010) — kept distinct
 * from [name] so a future enum-constant rename cannot silently change what is written to disk.
 */
enum class ViewId(val routeKey: String) {
    Device("device"),
    Apps("apps"),
    Network("network"),
    Logcat("logcat"),
    Settings("settings"),
    ;

    companion object {
        fun fromRouteKey(routeKey: String): ViewId? = entries.find { it.routeKey == routeKey }
    }
}
