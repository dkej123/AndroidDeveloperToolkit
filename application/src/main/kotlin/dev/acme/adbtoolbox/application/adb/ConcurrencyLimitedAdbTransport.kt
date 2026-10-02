package dev.acme.adbtoolbox.application.adb

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.process.ByteSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Lets at most [permitsPerDevice] one-shot text commands run on one device at a time; the rest wait
 * their turn before [delegate] starts them, so the wait never counts against a request's own
 * timeout. Entering the Device view fires ~15 reads at once (facts, display, toggles); on a slow
 * device they all shared the CPU and the short ones timed out ("Command timed out", a screenshot
 * timing out behind them — E2E 2026-10-01). Streams (logcat), binary transfers (screencap) and
 * server requests (`devices -l`) are passed straight through.
 */
class ConcurrencyLimitedAdbTransport(
    private val delegate: AdbTransport,
    private val permitsPerDevice: Int = DEFAULT_PERMITS_PER_DEVICE,
) : AdbTransport {
    private val lock = Mutex()
    private val semaphores = mutableMapOf<DeviceSerial, Semaphore>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        if (request !is AdbDeviceRequest) return delegate.executeText(request)
        return semaphoreFor(request.serial).withPermit { delegate.executeText(request) }
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = delegate.executeStream(request)

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = delegate.executeBinary(request, sink)

    private suspend fun semaphoreFor(serial: DeviceSerial): Semaphore =
        lock.withLock { semaphores.getOrPut(serial) { Semaphore(permitsPerDevice) } }

    companion object {
        const val DEFAULT_PERMITS_PER_DEVICE = 4
    }
}
