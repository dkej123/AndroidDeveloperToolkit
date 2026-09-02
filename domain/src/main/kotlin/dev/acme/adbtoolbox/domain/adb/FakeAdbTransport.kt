package dev.acme.adbtoolbox.domain.adb

import dev.acme.adbtoolbox.domain.process.ByteSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/** A scripted [AdbTransport.executeBinary] result: the byte chunks to deliver, plus the terminal outcome. */
data class AdbBinaryScript(val chunks: List<ByteArray>, val outcome: AdbOutcome)

private val DEFAULT_TEXT_RESULT = AdbTextResult(AdbOutcome.Completed(exitCode = 0), stdout = "", stderr = "")
private val DEFAULT_STREAM_SCRIPT = listOf<AdbStreamEvent>(AdbStreamEvent.Completed(AdbOutcome.Completed(0)))
private val DEFAULT_BINARY_SCRIPT = AdbBinaryScript(chunks = emptyList(), outcome = AdbOutcome.Completed(0))

/**
 * A deterministic [AdbTransport] test double: replays a caller-supplied script per gateway shape
 * instead of talking to ddmlib or a real `adb` process. Records every request per shape so tests
 * can assert on the exact [AdbRequest] a feature-local command factory built.
 */
class FakeAdbTransport(
    private val textScript: (AdbRequest) -> AdbTextResult = { DEFAULT_TEXT_RESULT },
    private val streamScript: (AdbRequest) -> List<AdbStreamEvent> = { DEFAULT_STREAM_SCRIPT },
    private val binaryScript: (AdbRequest) -> AdbBinaryScript = { DEFAULT_BINARY_SCRIPT },
) : AdbTransport {

    private val _textRequests = mutableListOf<AdbRequest>()
    val textRequests: List<AdbRequest> get() = _textRequests

    private val _streamRequests = mutableListOf<AdbRequest>()
    val streamRequests: List<AdbRequest> get() = _streamRequests

    private val _binaryRequests = mutableListOf<AdbRequest>()
    val binaryRequests: List<AdbRequest> get() = _binaryRequests

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        _textRequests += request
        return textScript(request)
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> {
        _streamRequests += request
        return streamScript(request).asFlow()
    }

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome {
        _binaryRequests += request
        val script = binaryScript(request)
        script.chunks.forEach(sink::write)
        return script.outcome
    }
}
