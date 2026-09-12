package dev.acme.adbtoolbox.application.apps

/** User-triggered inputs [AppsViewModel] reduces against [AppsViewState] (ADR 0004). */
sealed interface AppsIntent {

    /** Replaces the search query verbatim (case-insensitive label/package matching happens in the reducer). */
    data class SetQuery(val query: String) : AppsIntent

    /** The toolbar's "×" clear-search control, and the empty-state's "Clear filter" link. */
    data object ClearFilter : AppsIntent

    /** The toolbar's "Show system packages" toggle. */
    data object ToggleSystemPackages : AppsIntent

    /** Selects exactly [packageName] (`null` deselects) — never inferred from a row index. */
    data class SelectPackage(val packageName: String?) : AppsIntent

    /** The toolbar/action footer's refresh control. */
    data object Refresh : AppsIntent
}
