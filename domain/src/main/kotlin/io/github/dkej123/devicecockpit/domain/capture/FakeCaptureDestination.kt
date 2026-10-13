package io.github.dkej123.devicecockpit.domain.capture

import io.github.dkej123.devicecockpit.domain.process.ByteSink

/**
 * A deterministic [CaptureDestination] test double (mirrors [io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport]):
 * every [beginCapture] call records the bytes written to it and whether it was ultimately
 * [FakeCaptureTarget.commit]ted or [FakeCaptureTarget.discard]ed, so tests can assert both the exact
 * bytes a use case wrote and that a failed/cancelled capture was cleaned up rather than left behind.
 */
class FakeCaptureDestination(
    private val commitFailure: Throwable? = null,
    /** When set, every target offers this [CaptureTarget.writablePath]. */
    private val writablePath: String? = null,
) : CaptureDestination {

    private val _targets = mutableListOf<FakeCaptureTarget>()
    val targets: List<FakeCaptureTarget> get() = _targets

    override suspend fun beginCapture(baseFileName: String): CaptureTarget =
        FakeCaptureTarget(baseFileName, commitFailure, writablePath).also { _targets += it }
}

class FakeCaptureTarget(
    val baseFileName: String,
    private val commitFailure: Throwable?,
    override val writablePath: String? = null,
) : CaptureTarget {

    /** Bytes a host tool "wrote" to [writablePath], set by the test. */
    var externalBytes: Long = 0

    private val bytes = mutableListOf<Byte>()

    var committed: Boolean = false
        private set

    var discarded: Boolean = false
        private set

    var discardCount: Int = 0
        private set

    override val sink = ByteSink { chunk -> bytes += chunk.toList() }

    fun writtenBytes(): ByteArray = bytes.toByteArray()

    override suspend fun size(): Long = bytes.size + externalBytes

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
