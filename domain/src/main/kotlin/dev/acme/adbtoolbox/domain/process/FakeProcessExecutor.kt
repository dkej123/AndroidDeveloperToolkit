package dev.acme.adbtoolbox.domain.process

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * A deterministic [ProcessExecutor] test double: replays a fixed, caller-supplied event script for
 * every request instead of spawning a real process. Records every request it was asked to execute
 * so callers can assert on the exact [ProcessCommand] built for a given feature.
 */
class FakeProcessExecutor(
    private val script: (ProcessRequest) -> List<ProcessEvent>,
) : ProcessExecutor {
    private val _requests = mutableListOf<ProcessRequest>()
    val requests: List<ProcessRequest> get() = _requests

    override fun execute(request: ProcessRequest): Flow<ProcessEvent> {
        _requests += request
        return script(request).asFlow()
    }
}
