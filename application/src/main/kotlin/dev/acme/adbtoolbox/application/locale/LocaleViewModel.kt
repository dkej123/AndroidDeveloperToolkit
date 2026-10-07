package dev.acme.adbtoolbox.application.locale

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetOutcome
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetUseCase
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummary
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.locale.CatalogLocale
import dev.acme.adbtoolbox.domain.locale.LocaleCatalog
import dev.acme.adbtoolbox.domain.locale.LocaleTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A row of the Language & region list (design §3b). */
data class LocaleRow(val tag: String, val explanation: String)

/** Language & region (design §3b). [locale] comes from device readbacks only. */
data class LocaleViewState(
    val locale: DeviceLocaleState? = null,
    val loading: Boolean = true,
    val applying: String? = null,
    val error: String? = null,
    val query: String = "",
    val rows: List<LocaleRow> = TEST_LOCALES,
) {
    val searching: Boolean get() = query.isNotBlank()

    companion object {
        /** The quick picks shown while not searching, with the design's explanations. */
        val TEST_LOCALES = listOf(
            LocaleRow("en-XA", "Pseudo accented — longer, accented text in [brackets] shows truncation"),
            LocaleRow("ar-XB", "Pseudo RTL — mirrored layout with reversed English text"),
            LocaleRow("ar-EG", "Arabic — real RTL strings and Arabic-Indic digits"),
            LocaleRow("he-IL", "Hebrew — real RTL strings, Latin digits"),
        )
        const val MAX_RESULTS = 6
    }
}

sealed interface LocaleIntent {
    data class Search(val query: String) : LocaleIntent

    data class Apply(val tag: String) : LocaleIntent

    data object Reset : LocaleIntent

    data object Refresh : LocaleIntent
}

/** What a finished apply/reset should announce (design §3b toasts). */
sealed interface LocaleEvent {
    data class Applied(val tag: String) : LocaleEvent

    data class ResetTo(val tag: String) : LocaleEvent

    data class Failed(val message: String) : LocaleEvent
}

/**
 * Language & region (task 064, design §3b): searches the catalog, applies a locale through the
 * device helper and resets to the original; a changed locale is an override in the status-bar chip
 * and Reset all ([overrideContributor], [overrideResetUseCase]).
 */
class LocaleViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val useCase: DeviceLocaleUseCase,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val onEvent: (LocaleEvent) -> Unit = {},
) {
    private val _state = MutableStateFlow(LocaleViewState())
    val state: StateFlow<LocaleViewState> = _state.asStateFlow()

    private val context = MutableStateFlow(selectedDeviceState.value.toCommandContext())
    private val applyRequests = MutableSharedFlow<LocaleIntent>(extraBufferCapacity = 1)

    /** The confirmed locale per serial, for the override chip (readback truth only). */
    private val confirmed = mutableMapOf<DeviceSerial, DeviceLocaleState>()

    val overrideContributor = object : OverrideSummaryContributor {
        override fun overridesFor(serial: DeviceSerial?): List<OverrideSummary> {
            val locale = serial?.let(confirmed::get) ?: return emptyList()
            return if (locale.overridden) listOf(OverrideSummary(FEATURE_ID, "Language ${locale.current}")) else emptyList()
        }
    }

    val overrideResetUseCase = object : OverrideResetUseCase {
        override val featureId = FEATURE_ID

        override fun currentValue(serial: DeviceSerial): String? = confirmed[serial]?.takeIf { it.overridden }?.current

        override suspend fun reset(serial: DeviceSerial): OverrideResetOutcome {
            if (confirmed[serial]?.overridden != true) return OverrideResetOutcome.NoOverride
            return when (val result = useCase.reset(serial)) {
                is LocaleResult.Applied -> {
                    record(serial, result.state)
                    if (serial == eligibleSerial()) _state.update { it.copy(locale = result.state) }
                    if (result.state.overridden) OverrideResetOutcome.Failed("still ${result.state.current}") else OverrideResetOutcome.Success
                }
                is LocaleResult.Failed -> OverrideResetOutcome.Failed(result.reason)
            }
        }

        override suspend fun reapply(serial: DeviceSerial, value: String): OverrideResetOutcome {
            val tag = LocaleTag.of(value) ?: return OverrideResetOutcome.Failed("not a language tag: $value")
            return when (val result = useCase.set(serial, tag)) {
                is LocaleResult.Applied -> OverrideResetOutcome.Success.also { record(serial, result.state) }
                is LocaleResult.Failed -> OverrideResetOutcome.Failed(result.reason)
            }
        }
    }

    init {
        scope.launch(dispatchers.io) {
            selectedDeviceState.map { it.toCommandContext() }.distinctUntilChanged().collect { next ->
                context.value = next
                _state.update { LocaleViewState(query = it.query, rows = rowsFor(it.query)) }
                if (next is DeviceCommandContext.Eligible) refresh(next.serial)
            }
        }
        scope.launch(dispatchers.io) {
            applyRequests.collectLatest { intent ->
                val serial = eligibleSerial() ?: return@collectLatest
                when (intent) {
                    is LocaleIntent.Apply -> apply(serial, intent.tag)
                    LocaleIntent.Reset -> reset(serial)
                    else -> Unit
                }
            }
        }
    }

    fun handle(intent: LocaleIntent) {
        when (intent) {
            is LocaleIntent.Search -> _state.update { it.copy(query = intent.query, rows = rowsFor(intent.query)) }
            is LocaleIntent.Apply, LocaleIntent.Reset -> applyRequests.tryEmit(intent)
            LocaleIntent.Refresh -> eligibleSerial()?.let { serial -> scope.launch(dispatchers.io) { refresh(serial) } }
        }
    }

    private suspend fun refresh(serial: DeviceSerial) {
        val result = useCase.read(serial)
        if (eligibleSerial() != serial) return
        when (result) {
            is LocaleResult.Applied -> {
                record(serial, result.state)
                _state.update { it.copy(locale = result.state, loading = false, error = null) }
            }
            is LocaleResult.Failed -> _state.update { it.copy(loading = false, error = result.reason) }
        }
    }

    private suspend fun apply(serial: DeviceSerial, text: String) {
        val tag = LocaleTag.of(text) ?: run {
            onEvent(LocaleEvent.Failed("“$text” is not a language tag, e.g. pt-BR"))
            return
        }
        _state.update { it.copy(applying = tag.value) }
        val result = useCase.set(serial, tag)
        if (eligibleSerial() != serial) return
        finish(serial, result) { LocaleEvent.Applied(it.current) }
    }

    private suspend fun reset(serial: DeviceSerial) {
        _state.update { it.copy(applying = it.locale?.original) }
        val result = useCase.reset(serial)
        if (eligibleSerial() != serial) return
        finish(serial, result) { LocaleEvent.ResetTo(it.current) }
    }

    private fun finish(serial: DeviceSerial, result: LocaleResult, event: (DeviceLocaleState) -> LocaleEvent) {
        when (result) {
            is LocaleResult.Applied -> {
                record(serial, result.state)
                _state.update { it.copy(locale = result.state, applying = null, error = null, loading = false) }
                onEvent(event(result.state))
            }
            is LocaleResult.Failed -> {
                _state.update { it.copy(applying = null) }
                onEvent(LocaleEvent.Failed(result.reason))
            }
        }
    }

    private fun record(serial: DeviceSerial, locale: DeviceLocaleState) {
        confirmed[serial] = locale
    }

    private fun eligibleSerial(): DeviceSerial? = (context.value as? DeviceCommandContext.Eligible)?.serial

    private fun rowsFor(query: String): List<LocaleRow> {
        val q = query.trim()
        if (q.isEmpty()) return LocaleViewState.TEST_LOCALES
        val matches = LocaleCatalog.entries.filter { it.tag.value.contains(q, ignoreCase = true) || it.title.contains(q, ignoreCase = true) }
            .map { LocaleRow(it.tag.value, it.explanation()) }
        val typed = LocaleTag.of(q)?.takeIf { tag -> matches.none { it.tag.equals(tag.value, ignoreCase = true) } }
            ?.let { LocaleRow(it.value, "Use this language tag") }
        return (matches + listOfNotNull(typed)).take(LocaleViewState.MAX_RESULTS)
    }

    private fun CatalogLocale.explanation(): String =
        LocaleViewState.TEST_LOCALES.firstOrNull { it.tag == tag.value }?.explanation ?: title + if (rightToLeft) " (RTL)" else ""

    private companion object {
        const val FEATURE_ID = "locale"
    }
}
