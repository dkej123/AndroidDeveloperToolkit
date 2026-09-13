package dev.acme.adbtoolbox.application.settings

import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.settings.SettingsFieldError
import dev.acme.adbtoolbox.domain.settings.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable MVI state owner for the native project Settings form. */
class SettingsViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val settings: SettingsUseCase,
) {
    private val mutableState = MutableStateFlow(SettingsViewState())
    val state: StateFlow<SettingsViewState> = mutableState.asStateFlow()
    private var loadGeneration = 0L

    init {
        load()
    }

    fun handle(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.UpdateAdbPath -> edit(SettingsField.AdbPath) {
                it.copy(adbPathOverride = intent.value)
            }
            is SettingsIntent.UpdateScrcpyPath -> edit(SettingsField.ScrcpyPath) {
                it.copy(scrcpyPathOverride = intent.value)
            }
            is SettingsIntent.UpdateCaptureDirectory -> edit(SettingsField.CaptureDirectory) {
                it.copy(captureDirectory = intent.value)
            }
            is SettingsIntent.UpdateLogcatBufferSizeKb -> edit(SettingsField.LogcatBufferSize) {
                it.copy(logcatBufferSizeKb = intent.value)
            }
            SettingsIntent.Apply -> apply()
            SettingsIntent.Reset -> reset()
            SettingsIntent.RetryLoad -> load()
        }
    }

    private fun edit(field: SettingsField, transform: (SettingsState) -> SettingsState) {
        mutableState.update { current ->
            current.copy(
                draft = transform(current.draft),
                validationErrors = current.validationErrors - field.error,
                errorMessage = null,
            )
        }
    }

    private fun reset() {
        mutableState.update {
            it.copy(
                draft = it.persisted,
                validationErrors = emptySet(),
                errorMessage = null,
            )
        }
    }

    private fun load() {
        val generation = ++loadGeneration
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        scope.launch(dispatchers.io) {
            runCatching { settings.load() }
                .onSuccess { loaded ->
                    if (generation == loadGeneration) {
                        mutableState.value = SettingsViewState(
                            persisted = loaded,
                            draft = loaded,
                            isLoading = false,
                        )
                    }
                }
                .onFailure { failure ->
                    if (generation == loadGeneration) {
                        mutableState.update {
                            it.copy(isLoading = false, errorMessage = failure.describe())
                        }
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
            runCatching { settings.apply(candidate) }
                .onSuccess { result -> completeApply(candidate, result) }
                .onFailure { failure ->
                    mutableState.update { it.copy(isApplying = false, errorMessage = failure.describe()) }
                }
        }
    }

    private fun completeApply(candidate: SettingsState, result: SettingsApplyResult) {
        mutableState.update { current ->
            when (result) {
                is SettingsApplyResult.Applied -> current.copy(
                    persisted = result.state,
                    draft = if (current.draft == candidate) result.state else current.draft,
                    validationErrors = emptySet(),
                    isApplying = false,
                )
                is SettingsApplyResult.Invalid -> current.copy(
                    validationErrors = if (current.draft == candidate) result.errors else emptySet(),
                    isApplying = false,
                )
            }
        }
    }

    private enum class SettingsField(val error: SettingsFieldError) {
        AdbPath(SettingsFieldError.AdbPathNotExecutable),
        ScrcpyPath(SettingsFieldError.ScrcpyPathNotExecutable),
        CaptureDirectory(SettingsFieldError.CaptureDirectoryInvalid),
        LogcatBufferSize(SettingsFieldError.LogcatBufferSizeOutOfRange),
    }
}

private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "Unknown error"
