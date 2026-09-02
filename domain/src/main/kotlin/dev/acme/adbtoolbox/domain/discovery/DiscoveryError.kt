package dev.acme.adbtoolbox.domain.discovery

/** Every way [ToolLocator.locate] can fail to produce a [DiscoveredTool] — always carrying enough
 * to build an actionable message (never a bare/unstructured error), and always paired with a
 * [recovery] hint. */
sealed interface DiscoveryError {
    val toolId: ToolId
    val recovery: RecoveryAction

    /** No configured/SDK/PATH source produced an executable candidate at all. */
    data class ToolNotFound(
        override val toolId: ToolId,
        val attemptedSources: List<ToolSource>,
    ) : DiscoveryError {
        override val recovery: RecoveryAction = RecoveryAction.SetPath(toolId)
    }

    /** A candidate path was found at [source] but failed the executable-file check — covers both a
     * path that was never valid and one that used to resolve but no longer does (moved/deleted). */
    data class ExecutableInvalid(
        override val toolId: ToolId,
        val source: ToolSource,
        val path: String,
        val reason: String,
    ) : DiscoveryError {
        override val recovery: RecoveryAction = RecoveryAction.SetPath(toolId)
    }

    /** The executable at [path] validated but running its version query failed or produced output
     * that could not be parsed as a version. */
    data class VersionQueryFailed(
        override val toolId: ToolId,
        val source: ToolSource,
        val path: String,
        val reason: String,
    ) : DiscoveryError {
        override val recovery: RecoveryAction = RecoveryAction.SetPath(toolId)
    }
}
