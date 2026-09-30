package dev.acme.adbtoolbox.application.display.developer

import dev.acme.adbtoolbox.application.display.QuickToggleFieldState

/** The Developer-options switches of the Quick toggles section, in display order. */
enum class DeveloperToggle { StayAwake, DontKeepActivities, ShowViewUpdates, ShowSurfaceUpdates }

/**
 * State of [DeveloperOptionsViewModel]: one field per [DeveloperToggle] and the background process
 * limit ([dev.acme.adbtoolbox.domain.display.developer.BackgroundProcessLimit.STANDARD] when not overridden).
 */
data class DeveloperOptionsViewState(
    val toggles: Map<DeveloperToggle, QuickToggleFieldState<Boolean>> =
        DeveloperToggle.entries.associateWith { QuickToggleFieldState.Loading },
    val processLimit: QuickToggleFieldState<Int> = QuickToggleFieldState.Loading,
)

sealed interface DeveloperOptionsIntent {
    data class SetToggle(val toggle: DeveloperToggle, val enabled: Boolean) : DeveloperOptionsIntent

    data class SetProcessLimit(val limit: Int) : DeveloperOptionsIntent

    data object Refresh : DeveloperOptionsIntent
}
