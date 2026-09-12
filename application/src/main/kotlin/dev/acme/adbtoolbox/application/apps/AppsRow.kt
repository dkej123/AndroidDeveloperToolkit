package dev.acme.adbtoolbox.application.apps

/**
 * One rendered Apps-list row (task 022), reduced from [dev.acme.adbtoolbox.domain.packages.PackageEntry]
 * plus whether it is the current selection. No color/icon/spacing is decided here — only the data a
 * later rendering task needs (`design/README.md` §4's row/selection/"debug" tag treatment).
 */
data class AppsRow(
    val packageName: String,
    val label: String,
    val labelResolved: Boolean,
    val isDebuggable: Boolean?,
    val isSelected: Boolean,
)
