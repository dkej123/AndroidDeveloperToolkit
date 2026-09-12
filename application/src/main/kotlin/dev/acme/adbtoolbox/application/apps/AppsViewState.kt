package dev.acme.adbtoolbox.application.apps

/**
 * The Apps view's full reduced state (task 022). [rows] is already filtered by [query] and scoped
 * by [showSystemPackages] — the rendering layer never re-filters. [hasDevice] distinguishes "no
 * device selected" (nothing device-related renders) from a genuinely empty/loading list for an
 * online device; [isLoading] and [errorMessage] are mutually informative about why [rows] might be
 * empty even though [hasDevice] is `true`. [selectedPackageName] reflects
 * [dev.acme.adbtoolbox.domain.apps.SelectedPackageState] only when that selection's serial matches
 * the currently active device — a leftover selection belonging to a different device never
 * surfaces here (`design/README.md`'s "Selection sharing" note; selection must not cross devices).
 */
data class AppsViewState(
    val query: String = "",
    val showSystemPackages: Boolean = false,
    val hasDevice: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val rows: List<AppsRow> = emptyList(),
    val selectedPackageName: String? = null,
) {
    /** `true` only for the design's "no packages match the filter" empty state — a non-blank [query] with no matches. */
    val isFilteredEmpty: Boolean
        get() = hasDevice && !isLoading && errorMessage == null && rows.isEmpty() && query.isNotBlank()

    /** `true` when the device genuinely has zero (visible-scope) packages — distinct from a filtered-out list. */
    val isGenuinelyEmpty: Boolean
        get() = hasDevice && !isLoading && errorMessage == null && rows.isEmpty() && query.isBlank()
}
