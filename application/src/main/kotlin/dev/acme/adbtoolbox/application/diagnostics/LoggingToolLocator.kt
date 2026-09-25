package dev.acme.adbtoolbox.application.diagnostics

import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.DiagnosticsLog
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Records tool discovery: every change of the resolved adb/scrcpy (source, path, version) or of
 * the failure reason is logged at INFO/WARN; repeated identical results (every command resolves
 * adb through the cache) stay at DEBUG.
 */
class LoggingToolLocator(
    private val delegate: ToolLocator,
    private val log: DiagnosticsLog,
) : ToolLocator {
    private val lastOutcomes = MutableStateFlow<Map<ToolId, DiscoveryOutcome>>(emptyMap())

    override suspend fun locate(toolId: ToolId, forceRefresh: Boolean): DiscoveryOutcome {
        val outcome = delegate.locate(toolId, forceRefresh)
        val previous = lastOutcomes.getAndUpdate { it + (toolId to outcome) }[toolId]
        val changed = previous != outcome
        when (outcome) {
            is DiscoveryOutcome.Found -> log.log(
                if (changed) DiagLevel.INFO else DiagLevel.DEBUG,
                DiagCategory.DISCOVERY,
                "resolved",
                mapOf(
                    "tool" to toolId.name,
                    "source" to outcome.tool.source,
                    "path" to outcome.tool.path.value,
                    "version" to outcome.tool.version.toString(),
                    "forceRefresh" to forceRefresh,
                ),
            )

            is DiscoveryOutcome.Failed -> log.log(
                if (changed) DiagLevel.WARN else DiagLevel.DEBUG,
                DiagCategory.DISCOVERY,
                "not resolved",
                mapOf("tool" to toolId.name, "error" to outcome.error, "forceRefresh" to forceRefresh),
            )
        }
        return outcome
    }

    override suspend fun invalidate(toolId: ToolId) {
        log.log(DiagLevel.INFO, DiagCategory.DISCOVERY, "invalidated", mapOf("tool" to toolId.name))
        delegate.invalidate(toolId)
    }
}
