package io.github.dkej123.devicecockpit.application.display.developer

import io.github.dkej123.devicecockpit.application.display.QuickToggleFieldState

/** The Developer-options switches of the Quick toggles section, in display order. */
enum class DeveloperToggle { StayAwake, DontKeepActivities, ShowViewUpdates, ShowSurfaceUpdates }

/**
 * State of [DeveloperOptionsViewModel]: one field per [DeveloperToggle] and the background process
 * limit ([io.github.dkej123.devicecockpit.domain.display.developer.BackgroundProcessLimit.STANDARD] when not overridden).
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
