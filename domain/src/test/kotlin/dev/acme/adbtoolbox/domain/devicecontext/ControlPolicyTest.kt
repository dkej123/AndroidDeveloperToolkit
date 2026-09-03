package dev.acme.adbtoolbox.domain.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [ControlPolicy] is the presentation-neutral enabled/disabled+reason signal (task 014's scope:
 * "keeping all controls visible at the presentation-contract level" — this is never about hiding a
 * control, only whether it renders enabled). It composes on top of task 009's
 * [DeviceCommandContext] rather than re-deriving its own enabled/disabled vocabulary.
 */
class ControlPolicyTest {

    private val serial = DeviceSerial.of("R58N90ABCDE")
    private val onlineDevice = Device(serial, DeviceConnectionState.Online)
    private val unauthorizedDevice = Device(serial, DeviceConnectionState.Unauthorized)
    private val offlineDevice = Device(serial, DeviceConnectionState.Offline)
    private val ineligibleDevice = Device(serial, DeviceConnectionState.NoPermissions)

    @Test
    fun `Online device state yields Enabled`() {
        SelectedDeviceState.Online(onlineDevice).controlPolicy() shouldBe ControlPolicy.Enabled
    }

    @Test
    fun `Loading yields Disabled with the Loading reason`() {
        SelectedDeviceState.Loading.controlPolicy() shouldBe
            ControlPolicy.Disabled(DeviceCommandContext.Disabled.Loading)
    }

    @Test
    fun `None yields Disabled with the NoDeviceSelected reason`() {
        SelectedDeviceState.None.controlPolicy() shouldBe
            ControlPolicy.Disabled(DeviceCommandContext.Disabled.NoDeviceSelected)
    }

    @Test
    fun `Unauthorized yields Disabled carrying the connection state as the reason`() {
        SelectedDeviceState.Unauthorized(unauthorizedDevice).controlPolicy() shouldBe
            ControlPolicy.Disabled(DeviceCommandContext.Disabled.DeviceNotEligible(DeviceConnectionState.Unauthorized))
    }

    @Test
    fun `Offline yields Disabled carrying the connection state as the reason`() {
        SelectedDeviceState.Offline(offlineDevice).controlPolicy() shouldBe
            ControlPolicy.Disabled(DeviceCommandContext.Disabled.DeviceNotEligible(DeviceConnectionState.Offline))
    }

    @Test
    fun `Ineligible yields Disabled carrying the raw connection state, never coerced`() {
        SelectedDeviceState.Ineligible(ineligibleDevice).controlPolicy() shouldBe
            ControlPolicy.Disabled(DeviceCommandContext.Disabled.DeviceNotEligible(DeviceConnectionState.NoPermissions))
    }

    @Test
    fun `Disconnected yields Disabled carrying the stale serial`() {
        SelectedDeviceState.Disconnected(serial).controlPolicy() shouldBe
            ControlPolicy.Disabled(DeviceCommandContext.Disabled.DeviceStale(serial))
    }

    @Test
    fun `Error yields Disabled carrying the recoverable-failure message`() {
        SelectedDeviceState.Error("boom").controlPolicy() shouldBe
            ControlPolicy.Disabled(DeviceCommandContext.Disabled.SelectionError("boom"))
    }

    @Test
    fun `controlPolicy is derived consistently from DeviceCommandContext, never a duplicate vocabulary`() {
        val states: List<SelectedDeviceState> = listOf(
            SelectedDeviceState.Loading,
            SelectedDeviceState.None,
            SelectedDeviceState.Online(onlineDevice),
            SelectedDeviceState.Unauthorized(unauthorizedDevice),
            SelectedDeviceState.Offline(offlineDevice),
            SelectedDeviceState.Ineligible(ineligibleDevice),
            SelectedDeviceState.Disconnected(serial),
            SelectedDeviceState.Error("boom"),
        )

        states.forEach { state ->
            val expected = when (val context = state.toCommandContext()) {
                is DeviceCommandContext.Eligible -> ControlPolicy.Enabled
                is DeviceCommandContext.Disabled -> ControlPolicy.Disabled(context)
            }
            state.controlPolicy() shouldBe expected
        }
    }
}
