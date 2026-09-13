package dev.acme.adbtoolbox.application.mirroring

import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionFieldError
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsDraft
import dev.acme.adbtoolbox.domain.mirroring.toDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable MVI state owner for task 040's native mirroring-options editor, matching
 * [dev.acme.adbtoolbox.application.settings.SettingsViewModel]'s shape exactly.
 *
 * [currentOptions] is the seam [MirroringViewModel] reads from at `start()` time (never at edit
 * time): applying options here only ever changes what the *next* `start()` call passes to
 * [MirroringSessionManager] — an already-[dev.acme.adbtoolbox.domain.mirroring.MirroringSessionState.Running]
 * session's already-launched `scrcpy` process is never touched, matching task 040's "never mutates
 * an active session silently" acceptance criterion.
 */
class MirroringOptionsViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val options: MirroringOptionsUseCase,
) {
    private val mutableState = MutableStateFlow(MirroringOptionsViewState())
    val state: StateFlow<MirroringOptionsViewState> = mutableState.asStateFlow()

    val currentOptions: StateFlow<MirroringOptions> = mutableState
        .map { it.persisted.toOptionsOrDefault() }
        .stateIn(scope, SharingStarted.Eagerly, MirroringOptions.DEFAULT)

    private var loadGeneration = 0L

    init {
        load()
    }

    fun handle(intent: MirroringOptionsIntent) {
        when (intent) {
            is MirroringOptionsIntent.UpdateStayAwake -> edit(Field.StayAwake) { it.copy(stayAwake = intent.value) }
            is MirroringOptionsIntent.UpdateShowTouches -> edit(Field.ShowTouches) { it.copy(showTouches = intent.value) }
            is MirroringOptionsIntent.UpdateMaxSize -> edit(Field.MaxSize) { it.copy(maxSize = intent.value) }
            is MirroringOptionsIntent.UpdateVideoBitRateMbps -> edit(Field.VideoBitRate) { it.copy(videoBitRateMbps = intent.value) }
            MirroringOptionsIntent.Apply -> apply()
            MirroringOptionsIntent.Reset -> reset()
            MirroringOptionsIntent.RetryLoad -> load()
        }
    }

    private fun edit(field: Field, transform: (MirroringOptionsDraft) -> MirroringOptionsDraft) {
        mutableState.update { current ->
            current.copy(
                draft = transform(current.draft),
                validationErrors = current.validationErrors - field.errors,
                errorMessage = null,
            )
        }
    }

    private fun reset() {
        mutableState.update {
            it.copy(draft = it.persisted, validationErrors = emptySet(), errorMessage = null)
        }
    }

    private fun load() {
        val generation = ++loadGeneration
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        scope.launch(dispatchers.io) {
            runCatching { options.load() }
                .onSuccess { loaded ->
                    if (generation == loadGeneration) {
                        val draft = loaded.toDraft()
                        mutableState.value = MirroringOptionsViewState(persisted = draft, draft = draft, isLoading = false)
                    }
                }
                .onFailure { failure ->
                    if (generation == loadGeneration) {
                        mutableState.update { it.copy(isLoading = false, errorMessage = failure.describe()) }
                    }
                }
        }
    }

    private fun apply() {
        val current = mutableState.value
        if (current.isLoading || current.isApplying) return
        val candidate = current.draft
        mutableState.update { it.copy(isApplying = true, validationErrors = emptySet(), errorMessage = null) }
        scope.launch(dispatchers.io) {
            runCatching { options.apply(candidate) }
                .onSuccess { result -> completeApply(candidate, result) }
                .onFailure { failure ->
                    mutableState.update { it.copy(isApplying = false, errorMessage = failure.describe()) }
                }
        }
    }

    private fun completeApply(candidate: MirroringOptionsDraft, result: MirroringOptionsApplyResult) {
        mutableState.update { current ->
            when (result) {
                is MirroringOptionsApplyResult.Applied -> {
                    val appliedDraft = result.options.toDraft()
                    current.copy(
                        persisted = appliedDraft,
                        draft = if (current.draft == candidate) appliedDraft else current.draft,
                        validationErrors = emptySet(),
                        isApplying = false,
                    )
                }

                is MirroringOptionsApplyResult.Invalid -> current.copy(
                    validationErrors = if (current.draft == candidate) result.errors else emptySet(),
                    isApplying = false,
                )
            }
        }
    }

    private enum class Field(val errors: Set<MirroringOptionFieldError>) {
        StayAwake(emptySet()),
        ShowTouches(emptySet()),
        MaxSize(setOf(MirroringOptionFieldError.MaxSizeOutOfRange)),
        VideoBitRate(setOf(MirroringOptionFieldError.VideoBitRateOutOfRange)),
    }
}

/** [MirroringOptionsViewState.persisted] is always a value that once validated successfully (loaded
 * from [MirroringOptionsUseCase.load] or just applied), so this reconstruction can never actually
 * throw in practice — falling back to [MirroringOptions.DEFAULT] only guards a corrupt in-memory
 * state from ever crashing a `start()` call. */
private fun MirroringOptionsDraft.toOptionsOrDefault(): MirroringOptions =
    runCatching { MirroringOptions(stayAwake, showTouches, maxSize, videoBitRateMbps) }
        .getOrDefault(MirroringOptions.DEFAULT)

private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "Unknown error"
