package dev.acme.adbtoolbox.application.mirroring

import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionFieldError
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsDraft
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsRepository
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsValidationResult
import dev.acme.adbtoolbox.domain.mirroring.validateMirroringOptions

sealed interface MirroringOptionsApplyResult {
    data class Applied(val options: MirroringOptions) : MirroringOptionsApplyResult
    data class Invalid(val errors: Set<MirroringOptionFieldError>) : MirroringOptionsApplyResult
}

/** Validates and persists one project mirroring-options snapshot (task 040), matching
 * [dev.acme.adbtoolbox.application.settings.SettingsUseCase]'s contract. Unlike settings, no
 * dependent runtime state needs invalidating here: a change only ever affects the next `start()`
 * call, read fresh from [repository] at that time — never an already-running session. */
class MirroringOptionsUseCase(
    private val repository: MirroringOptionsRepository,
) {
    suspend fun load(): MirroringOptions = repository.readOptions()

    suspend fun apply(candidate: MirroringOptionsDraft): MirroringOptionsApplyResult =
        when (val validation = validateMirroringOptions(candidate)) {
            is MirroringOptionsValidationResult.Invalid -> MirroringOptionsApplyResult.Invalid(validation.errors)
            is MirroringOptionsValidationResult.Valid -> {
                repository.writeOptions(validation.options)
                MirroringOptionsApplyResult.Applied(validation.options)
            }
        }
}
