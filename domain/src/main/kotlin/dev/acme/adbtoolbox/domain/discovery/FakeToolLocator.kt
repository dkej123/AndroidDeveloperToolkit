package dev.acme.adbtoolbox.domain.discovery

/**
 * A deterministic [ToolLocator] test double: returns a caller-supplied [DiscoveryOutcome] per
 * [ToolId] instead of resolving a real executable. Records every [locate] call so tests can assert
 * a transport resolved the tool it needed rather than assuming a fixed path.
 */
class FakeToolLocator(
    private val outcomes: (ToolId) -> DiscoveryOutcome,
) : ToolLocator {

    private val _locateCalls = mutableListOf<ToolId>()
    val locateCalls: List<ToolId> get() = _locateCalls

    private val _invalidated = mutableListOf<ToolId>()
    val invalidated: List<ToolId> get() = _invalidated

    override suspend fun locate(toolId: ToolId, forceRefresh: Boolean): DiscoveryOutcome {
        _locateCalls += toolId
        return outcomes(toolId)
    }

    override suspend fun invalidate(toolId: ToolId) {
        _invalidated += toolId
    }
}
