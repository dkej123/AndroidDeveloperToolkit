package io.github.dkej123.devicecockpit.application.adb

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbServerRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbStreamEvent
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Gate-controlled transport: every executeText suspends until [release] and records concurrency. */
private class GatedTransport : AdbTransport {
    val gate = CompletableDeferred<Unit>()
    var running = 0
    var peak = 0
    val peakPerSerial = mutableMapOf<String, Int>()
    private val runningPerSerial = mutableMapOf<String, Int>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        val key = (request as? AdbDeviceRequest)?.serial?.toString() ?: "server"
        running++
        runningPerSerial[key] = (runningPerSerial[key] ?: 0) + 1
        peak = maxOf(peak, running)
        peakPerSerial[key] = maxOf(peakPerSerial[key] ?: 0, runningPerSerial.getValue(key))
        try {
            gate.await()
        } finally {
            running--
            runningPerSerial[key] = runningPerSerial.getValue(key) - 1
        }
        return AdbTextResult(AdbOutcome.Completed(0), "", "")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = flowOf()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}

private fun shell(serial: String) = AdbDeviceRequest(DeviceSerial.of(serial), AdbOperation.Exec(listOf("getprop")))

class ConcurrencyLimitedAdbTransportTest {

    @Test
    fun `at most the permitted number of device commands run at once, the rest wait their turn`() = runTest {
        val delegate = GatedTransport()
        val transport = ConcurrencyLimitedAdbTransport(delegate, permitsPerDevice = 3)

        val calls = List(10) { launch { transport.executeText(shell("emulator-5554")) } }
        runCurrent()

        delegate.running shouldBe 3
        delegate.gate.complete(Unit)
        calls.forEach { it.join() }
        delegate.peak shouldBe 3
        delegate.running shouldBe 0
    }

    @Test
    fun `each device has its own limit and server requests are never queued`() = runTest {
        val delegate = GatedTransport()
        val transport = ConcurrencyLimitedAdbTransport(delegate, permitsPerDevice = 2)

        val calls = List(4) { launch { transport.executeText(shell("emulator-5554")) } } +
            List(4) { launch { transport.executeText(shell("192.168.1.20:5555")) } } +
            List(3) { launch { transport.executeText(AdbServerRequest(listOf("devices", "-l"))) } }
        runCurrent()

        delegate.peakPerSerial["emulator-5554"] shouldBe 2
        delegate.peakPerSerial["192.168.1.20:5555"] shouldBe 2
        delegate.peakPerSerial["server"] shouldBe 3
        delegate.gate.complete(Unit)
        calls.forEach { it.join() }
    }

    @Test
    fun `a cancelled caller gives its turn back`() = runTest {
        val delegate = GatedTransport()
        val transport = ConcurrencyLimitedAdbTransport(delegate, permitsPerDevice = 1)

        val first = launch { transport.executeText(shell("emulator-5554")) }
        runCurrent()
        first.cancel()
        runCurrent()
        val second = launch { transport.executeText(shell("emulator-5554")) }
        runCurrent()

        delegate.running shouldBe 1
        delegate.gate.complete(Unit)
        second.join()
    }
}
