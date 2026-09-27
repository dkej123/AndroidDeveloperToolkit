package dev.acme.adbtoolbox.application.mirroring

import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionFieldError
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsDraft
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsRepository
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsValidationResult
import dev.acme.adbtoolbox.domain.mirroring.validateMirroringOptions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface MirroringOptionsApplyResult {
    data class Applied(val options: MirroringOptions) : MirroringOptionsApplyResult
    data class Invalid(val errors: Set<MirroringOptionFieldError>) : MirroringOptionsApplyResult
}

/** Validates and persists one project mirroring-options snapshot (task 040), matching
 * [dev.acme.adbtoolbox.application.settings.SettingsUseCase]'s contract. A change only ever affects
 * the next `start()` call — never an already-running session. Every successful apply is announced
 * on [applied], so [MirroringOptionsViewModel] (which `start()` reads from) stays current even when
 * the options dialog applies through this use case directly. */
class MirroringOptionsUseCase(
    private val repository: MirroringOptionsRepository,
) {
    private val appliedOptions = MutableSharedFlow<MirroringOptions>(extraBufferCapacity = 16)
    val applied: SharedFlow<MirroringOptions> = appliedOptions.asSharedFlow()

    suspend fun load(): MirroringOptions = repository.readOptions()

    suspend fun apply(candidate: MirroringOptionsDraft): MirroringOptionsApplyResult =
        when (val validation = validateMirroringOptions(candidate)) {
            is MirroringOptionsValidationResult.Invalid -> MirroringOptionsApplyResult.Invalid(validation.errors)
            is MirroringOptionsValidationResult.Valid -> {
                repository.writeOptions(validation.options)
                appliedOptions.emit(validation.options)
                MirroringOptionsApplyResult.Applied(validation.options)
            }
        }
}
