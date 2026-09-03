package dev.acme.adbtoolbox.domain.device

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class DeviceCommandContextTest {

    private val serial = DeviceSerial.of("R58N90ABCDE")
    private val onlineDevice = Device(serial, DeviceConnectionState.Online)
    private val unauthorizedDevice = Device(serial, DeviceConnectionState.Unauthorized)
    private val offlineDevice = Device(serial, DeviceConnectionState.Offline)
    private val ineligibleDevice = Device(serial, DeviceConnectionState.NoPermissions)

    @Test
    fun `Online selection produces an Eligible context carrying the exact serial`() {
        val context = SelectedDeviceState.Online(onlineDevice).toCommandContext()

        context shouldBe DeviceCommandContext.Eligible(serial)
    }

    @Test
    fun `Loading produces Disabled Loading`() {
        SelectedDeviceState.Loading.toCommandContext() shouldBe DeviceCommandContext.Disabled.Loading
    }

    @Test
    fun `None produces Disabled NoDeviceSelected`() {
        SelectedDeviceState.None.toCommandContext() shouldBe DeviceCommandContext.Disabled.NoDeviceSelected
    }

    @Test
    fun `Unauthorized produces Disabled DeviceNotEligible carrying the connection state`() {
        val context = SelectedDeviceState.Unauthorized(unauthorizedDevice).toCommandContext()

        context shouldBe DeviceCommandContext.Disabled.DeviceNotEligible(DeviceConnectionState.Unauthorized)
    }

    @Test
    fun `Offline produces Disabled DeviceNotEligible carrying the connection state`() {
        val context = SelectedDeviceState.Offline(offlineDevice).toCommandContext()

        context shouldBe DeviceCommandContext.Disabled.DeviceNotEligible(DeviceConnectionState.Offline)
    }

    @Test
    fun `Ineligible produces Disabled DeviceNotEligible carrying the raw connection state, never coerced`() {
        val context = SelectedDeviceState.Ineligible(ineligibleDevice).toCommandContext()

        context shouldBe DeviceCommandContext.Disabled.DeviceNotEligible(DeviceConnectionState.NoPermissions)
    }

    @Test
    fun `Disconnected produces Disabled DeviceStale carrying the stale serial, never another device`() {
        val context = SelectedDeviceState.Disconnected(serial).toCommandContext()

        context shouldBe DeviceCommandContext.Disabled.DeviceStale(serial)
    }

    @Test
    fun `Error produces Disabled SelectionError carrying the message`() {
        val context = SelectedDeviceState.Error("boom").toCommandContext()

        context shouldBe DeviceCommandContext.Disabled.SelectionError("boom")
    }

    @Test
    fun `only Eligible ever carries a serial usable for a command`() {
        val nonEligible: List<SelectedDeviceState> = listOf(
            SelectedDeviceState.Loading,
            SelectedDeviceState.None,
            SelectedDeviceState.Unauthorized(unauthorizedDevice),
            SelectedDeviceState.Offline(offlineDevice),
            SelectedDeviceState.Ineligible(ineligibleDevice),
            SelectedDeviceState.Disconnected(serial),
            SelectedDeviceState.Error("boom"),
        )

        nonEligible.forEach { state ->
            state.toCommandContext().shouldBeInstanceOf<DeviceCommandContext.Disabled>()
        }
    }
}
