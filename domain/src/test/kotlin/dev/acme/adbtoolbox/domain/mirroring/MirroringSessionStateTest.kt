package dev.acme.adbtoolbox.domain.mirroring

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolSource
import dev.acme.adbtoolbox.domain.discovery.ToolVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("R58N90ABCDE")

class MirroringSessionStateTest {

    @Test
    fun `every state variant retains its originating serial`() {
        val states: List<MirroringSessionState> = listOf(
            MirroringSessionState.Idle(serial),
            MirroringSessionState.Starting(serial),
            MirroringSessionState.Running(serial, ToolVersion.of("2.7")),
            MirroringSessionState.Stopping(serial),
            MirroringSessionState.Exited(serial, MirroringExitReason.Requested),
            MirroringSessionState.Error(serial, MirroringSessionError.StartFailure("boom")),
        )

        states.forEach { it.serial shouldBe serial }
    }

    @Test
    fun `Exited external window exit is a distinct reason from a requested stop`() {
        val requested = MirroringSessionState.Exited(serial, MirroringExitReason.Requested)
        val externalExit = MirroringSessionState.Exited(serial, MirroringExitReason.ExternalWindowExit)

        (requested.reason == externalExit.reason) shouldBe false
    }

    @Test
    fun `missing scrcpy is an actionable, typed error carrying the discovery failure`() {
        val discoveryError = DiscoveryError.ToolNotFound(ToolId.Scrcpy, listOf(ToolSource.PathFallback))
        val state = MirroringSessionState.Error(serial, MirroringSessionError.ToolUnavailable(discoveryError))

        (state.error as MirroringSessionError.ToolUnavailable).error shouldBe discoveryError
    }
}
