package dev.acme.adbtoolbox.domain.devicefacts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeviceFactsCommandsTest {

    @Test
    fun `android version command chains the two getprop calls`() {
        val command = DeviceFactsCommands.commandFor(DeviceFactId.AndroidVersion)
        command.render() shouldBe "getprop ro.build.version.release ; getprop ro.build.version.sdk"
    }

    @Test
    fun `resolution command is wm size`() {
        DeviceFactsCommands.commandFor(DeviceFactId.Resolution).render() shouldBe "wm size"
    }

    @Test
    fun `density command is wm density`() {
        DeviceFactsCommands.commandFor(DeviceFactId.Density).render() shouldBe "wm density"
    }

    @Test
    fun `battery command is dumpsys battery`() {
        DeviceFactsCommands.commandFor(DeviceFactId.Battery).render() shouldBe "dumpsys battery"
    }

    @Test
    fun `abi command reads the cpu abi prop`() {
        DeviceFactsCommands.commandFor(DeviceFactId.Abi).render() shouldBe "getprop ro.product.cpu.abi"
    }

    @Test
    fun `uptime command is uptime`() {
        DeviceFactsCommands.commandFor(DeviceFactId.Uptime).render() shouldBe "uptime"
    }

    @Test
    fun `every DeviceFactId has a command`() {
        DeviceFactId.entries.forEach { factId ->
            DeviceFactsCommands.commandFor(factId).render()
        }
    }
}
