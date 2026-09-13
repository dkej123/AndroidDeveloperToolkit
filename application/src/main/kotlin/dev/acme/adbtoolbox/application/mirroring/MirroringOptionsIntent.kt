package dev.acme.adbtoolbox.application.mirroring

/** The single user-triggered input [MirroringOptionsViewModel] reduces against (ADR 0004),
 * matching [dev.acme.adbtoolbox.application.settings.SettingsIntent]'s shape. */
sealed interface MirroringOptionsIntent {
    data class UpdateStayAwake(val value: Boolean) : MirroringOptionsIntent
    data class UpdateShowTouches(val value: Boolean) : MirroringOptionsIntent
    data class UpdateMaxSize(val value: Int?) : MirroringOptionsIntent
    data class UpdateVideoBitRateMbps(val value: Int?) : MirroringOptionsIntent
    data object Apply : MirroringOptionsIntent
    data object Reset : MirroringOptionsIntent
    data object RetryLoad : MirroringOptionsIntent
}
