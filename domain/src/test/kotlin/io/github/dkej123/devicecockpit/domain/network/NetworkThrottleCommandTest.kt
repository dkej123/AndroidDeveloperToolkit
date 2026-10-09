package io.github.dkej123.devicecockpit.domain.network

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val EMULATOR = DeviceSerial.of("emulator-5554")

private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

private fun status(downBits: Long, minMs: Int, maxMs: Int) = """
    Current network status:
      download speed:     $downBits bits/s (0 KB/s)
      upload speed:       $downBits bits/s (0 KB/s)
      minimum latency:  $minMs ms
      maximum latency:  $maxMs ms
    OK
""".trimIndent()

class NetworkThrottleCommandTest {

    @Test
    fun `only emulators support throttling`() {
        NetworkThrottleCommand.isSupported(EMULATOR) shouldBe true
        NetworkThrottleCommand.isSupported(DeviceSerial.of("R58N90ABCDE")) shouldBe false
        NetworkThrottleCommand.isSupported(DeviceSerial.of("192.168.1.20:5555")) shouldBe false
    }

    @Test
    fun `a preset sets both the emulator speed and latency through the emulator console`() {
        NetworkThrottleCommand.applyRequests(EMULATOR, NetworkThrottle.Edge).map { it.operation } shouldBe listOf(
            AdbOperation.Host(listOf("emu", "network", "speed", "edge")),
            AdbOperation.Host(listOf("emu", "network", "delay", "edge")),
        )
        NetworkThrottleCommand.applyRequests(EMULATOR, NetworkThrottle.Off).map { it.operation } shouldBe listOf(
            AdbOperation.Host(listOf("emu", "network", "speed", "full")),
            AdbOperation.Host(listOf("emu", "network", "delay", "none")),
        )
        NetworkThrottleCommand.statusRequest(EMULATOR).operation shouldBe AdbOperation.Host(listOf("emu", "network", "status"))
    }

    @Test
    fun `the status readback is matched back to a preset`() {
        NetworkThrottleCommand.parseStatus(ok(status(0, 0, 0))) shouldBe NetworkThrottleRead.Known(NetworkThrottle.Off)
        NetworkThrottleCommand.parseStatus(ok(status(473_600, 80, 400))) shouldBe NetworkThrottleRead.Known(NetworkThrottle.Edge)
        NetworkThrottleCommand.parseStatus(ok(status(384_000, 35, 200))) shouldBe NetworkThrottleRead.Known(NetworkThrottle.Umts)
    }

    @Test
    fun `values set outside the plugin are reported as custom, and garbage as unreadable`() {
        NetworkThrottleCommand.parseStatus(ok(status(473_600, 0, 0))) shouldBe NetworkThrottleRead.Custom(downloadBitsPerSecond = 473_600, maxLatencyMs = 0)
        NetworkThrottleCommand.parseStatus(ok("KO: unknown command")) shouldBe NetworkThrottleRead.Unreadable("KO: unknown command")
        NetworkThrottleCommand.parseStatus(AdbTextResult(AdbOutcome.TimedOut, "", "")) shouldBe NetworkThrottleRead.Unreadable("timed out")
    }

    @Test
    fun `a write is accepted only when the console answers OK`() {
        NetworkThrottleCommand.isAccepted(ok("OK\n")) shouldBe true
        NetworkThrottleCommand.isAccepted(ok("KO: invalid <speed> argument")) shouldBe false
    }
}
