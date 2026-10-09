package io.github.dkej123.devicecockpit.application.mirroring

import io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptionFieldError
import io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptions
import io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptionsDraft
import io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptionsRepository
import io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptionsValidationResult
import io.github.dkej123.devicecockpit.domain.mirroring.validateMirroringOptions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface MirroringOptionsApplyResult {
    data class Applied(val options: MirroringOptions) : MirroringOptionsApplyResult
    data class Invalid(val errors: Set<MirroringOptionFieldError>) : MirroringOptionsApplyResult
}

/** Validates and persists one project mirroring-options snapshot (task 040), matching
 * [io.github.dkej123.devicecockpit.application.settings.SettingsUseCase]'s contract. A change only ever affects
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
