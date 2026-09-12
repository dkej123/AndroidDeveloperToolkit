package dev.acme.adbtoolbox.domain.capture

import dev.acme.adbtoolbox.domain.process.ByteSink

/**
 * A deterministic [CaptureDestination] test double (mirrors [dev.acme.adbtoolbox.domain.adb.FakeAdbTransport]):
 * every [beginCapture] call records the bytes written to it and whether it was ultimately
 * [FakeCaptureTarget.commit]ted or [FakeCaptureTarget.discard]ed, so tests can assert both the exact
 * bytes a use case wrote and that a failed/cancelled capture was cleaned up rather than left behind.
 */
class FakeCaptureDestination(
    private val commitFailure: Throwable? = null,
) : CaptureDestination {

    private val _targets = mutableListOf<FakeCaptureTarget>()
    val targets: List<FakeCaptureTarget> get() = _targets

    override suspend fun beginCapture(baseFileName: String): CaptureTarget =
        FakeCaptureTarget(baseFileName, commitFailure).also { _targets += it }
}

class FakeCaptureTarget(
    val baseFileName: String,
    private val commitFailure: Throwable?,
) : CaptureTarget {

    private val bytes = mutableListOf<Byte>()

    var committed: Boolean = false
        private set

    var discarded: Boolean = false
        private set

    var discardCount: Int = 0
        private set

    override val sink = ByteSink { chunk -> bytes += chunk.toList() }

    fun writtenBytes(): ByteArray = bytes.toByteArray()

    override suspend fun commit(): CaptureLocation {
        commitFailure?.let { throw it }
        committed = true
        return CaptureLocation(displayPath = "/fake/captures/$baseFileName")
    }

    override suspend fun discard() {
        discarded = true
        discardCount++
    }
}
