package dev.acme.adbtoolbox.domain.mirroring

import dev.acme.adbtoolbox.domain.discovery.DiscoveryError

/** Every way starting/running a mirroring session can fail outright — always actionable, never a
 * bare/unstructured error. */
sealed interface MirroringSessionError {

    /** `scrcpy` could not be resolved by [dev.acme.adbtoolbox.domain.discovery.ToolLocator] —
     * carries task 004's own [DiscoveryError] (and its [DiscoveryError.recovery] hint) verbatim so
     * a later presentation task renders the exact same "scrcpy not found, configure path" recovery
     * story tool discovery already defines, rather than inventing a second one. */
    data class ToolUnavailable(val error: DiscoveryError) : MirroringSessionError

    /** The resolved `scrcpy` executable was invoked but the OS process failed to start, or exited
     * before ever producing output, for [reason]. */
    data class StartFailure(val reason: String) : MirroringSessionError
}
