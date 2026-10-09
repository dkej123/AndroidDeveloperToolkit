package io.github.dkej123.devicecockpit.application.mirroring

import io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptionFieldError
import io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptionsDraft

/** The immutable state task 040's options editor renders from and edits, matching
 * [io.github.dkej123.devicecockpit.application.settings.SettingsViewState]'s shape. */
data class MirroringOptionsViewState(
    val persisted: MirroringOptionsDraft = MirroringOptionsDraft.DEFAULT,
    val draft: MirroringOptionsDraft = MirroringOptionsDraft.DEFAULT,
    val validationErrors: Set<MirroringOptionFieldError> = emptySet(),
    val isLoading: Boolean = true,
    val isApplying: Boolean = false,
    val errorMessage: String? = null,
) {
    val isModified: Boolean get() = draft != persisted
}
