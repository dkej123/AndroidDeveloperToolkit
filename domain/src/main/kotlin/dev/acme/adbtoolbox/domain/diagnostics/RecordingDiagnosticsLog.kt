package dev.acme.adbtoolbox.domain.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** One captured [DiagnosticsLog] call. */
data class DiagEntry(
    val level: DiagLevel,
    val category: String,
    val message: String,
    val fields: Map<String, Any?>,
    val error: Throwable?,
)

/**
 * A deterministic [DiagnosticsLog] test double that keeps every entry in memory (mirrors
 * [dev.acme.adbtoolbox.domain.device.FakeDeviceRepository]). [minLevel] models the verbose toggle.
 */
class RecordingDiagnosticsLog(private val minLevel: DiagLevel = DiagLevel.DEBUG) : DiagnosticsLog {
    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    val entries: List<DiagEntry> get() = _entries.value

    override fun isEnabled(level: DiagLevel): Boolean = level >= minLevel

    override fun log(level: DiagLevel, category: String, message: String, fields: Map<String, Any?>, error: Throwable?) {
        if (!isEnabled(level)) return
        _entries.update { it + DiagEntry(level, category, message, fields, error) }
    }

    fun inCategory(category: String): List<DiagEntry> = entries.filter { it.category == category }
}
