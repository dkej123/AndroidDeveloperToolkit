@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.BadgeContributor
import dev.acme.adbtoolbox.domain.devicecontext.DeviceContextSnapshot
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummary
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.devicecontext.RunningProcessContributor
import dev.acme.adbtoolbox.domain.devicecontext.RunningProcessInfo
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private fun onlineState(serial: DeviceSerial) =
    SelectedDeviceState.Online(Device(serial = serial, state = DeviceConnectionState.Online))

internal class FakeBadgeContributor(
    override val viewId: ViewId,
    var badgesBySerial: Map<DeviceSerial?, NavigationBadge> = emptyMap(),
) : BadgeContributor {
    override fun badgeFor(serial: DeviceSerial?): NavigationBadge = badgesBySerial[serial] ?: NavigationBadge.None
}

private class FakeRunningProcessContributor(
    var processesBySerial: Map<DeviceSerial?, List<RunningProcessInfo>> = emptyMap(),
) : RunningProcessContributor {
    override fun runningProcessesFor(serial: DeviceSerial?): List<RunningProcessInfo> = processesBySerial[serial].orEmpty()
}

private class FakeOverrideSummaryContributor(
    var overridesBySerial: Map<DeviceSerial?, List<OverrideSummary>> = emptyMap(),
) : OverrideSummaryContributor {
    override fun overridesFor(serial: DeviceSerial?): List<OverrideSummary> = overridesBySerial[serial].orEmpty()
}

class DeviceContextAggregatorTest {

    private fun harness(): Triple<TestScope, MutableStateFlow<SelectedDeviceState>, DeviceContextAggregator> {
        val scope = TestScope()
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val aggregator = DeviceContextAggregator(scope = scope, selectedDeviceState = selectedDeviceState)
        return Triple(scope, selectedDeviceState, aggregator)
    }

    @Test
    fun `with no device selected and no contributors, the snapshot is empty for a null serial`() = runTest {
        val (scope, _, aggregator) = harness()
        scope.runCurrent()

        aggregator.state.value shouldBe DeviceContextSnapshot(null, emptyMap(), emptyList(), emptyList())
    }

    @Test
    fun `registering contributors of every kind combines them into the snapshot for the selected serial`() = runTest {
        val (scope, selectedDeviceState, aggregator) = harness()
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        aggregator.registerBadgeContributor(
            FakeBadgeContributor(ViewId.Logcat, mapOf(serialA to NavigationBadge.Attention)),
        )
        aggregator.registerRunningProcessContributor(
            FakeRunningProcessContributor(mapOf(serialA to listOf(RunningProcessInfo("scrcpy", "Mirroring")))),
        )
        aggregator.registerOverrideSummaryContributor(
            FakeOverrideSummaryContributor(mapOf(serialA to listOf(OverrideSummary("font-scale", "1.3x")))),
        )
        scope.runCurrent()

        aggregator.state.value shouldBe DeviceContextSnapshot(
            serial = serialA,
            badges = mapOf(ViewId.Logcat to NavigationBadge.Attention),
            runningProcesses = listOf(RunningProcessInfo("scrcpy", "Mirroring")),
            overrides = listOf(OverrideSummary("font-scale", "1.3x")),
        )
    }

    @Test
    fun `switching the selected serial fully recomputes the snapshot and drops the old serial's data`() = runTest {
        val (scope, selectedDeviceState, aggregator) = harness()
        val processes = FakeRunningProcessContributor(
            mapOf(
                serialA to listOf(RunningProcessInfo("scrcpy", "Mirroring A")),
                serialB to listOf(RunningProcessInfo("scrcpy", "Mirroring B")),
            ),
        )
        aggregator.registerRunningProcessContributor(processes)

        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        aggregator.state.value shouldBe DeviceContextSnapshot(
            serialA,
            emptyMap(),
            listOf(RunningProcessInfo("scrcpy", "Mirroring A")),
            emptyList(),
        )

        selectedDeviceState.value = onlineState(serialB)
        scope.runCurrent()
        aggregator.state.value shouldBe DeviceContextSnapshot(
            serialB,
            emptyMap(),
            listOf(RunningProcessInfo("scrcpy", "Mirroring B")),
            emptyList(),
        )
    }

    @Test
    fun `unregistering a contributor removes its contribution from later snapshots`() = runTest {
        val (scope, selectedDeviceState, aggregator) = harness()
        selectedDeviceState.value = onlineState(serialA)
        val registration = aggregator.registerOverrideSummaryContributor(
            FakeOverrideSummaryContributor(mapOf(serialA to listOf(OverrideSummary("proxy", "10.0.0.1:8080")))),
        )
        scope.runCurrent()
        aggregator.state.value.overrides shouldBe listOf(OverrideSummary("proxy", "10.0.0.1:8080"))

        registration.unregister()
        scope.runCurrent()

        aggregator.state.value.overrides shouldBe emptyList()
    }

    @Test
    fun `cancelling the owning scope tears the aggregator down cleanly, with no further recomputation`() = runTest {
        val (scope, selectedDeviceState, aggregator) = harness()
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        val lastSnapshot = aggregator.state.value

        scope.cancel()
        selectedDeviceState.value = onlineState(serialB)
        scope.runCurrent()

        aggregator.state.value shouldBe lastSnapshot
    }

    @Test
    fun `two independently-stateful contributors never share mutable storage`() = runTest {
        val (scope, selectedDeviceState, aggregator) = harness()
        selectedDeviceState.value = onlineState(serialA)
        val contributorA = FakeOverrideSummaryContributor(mapOf(serialA to listOf(OverrideSummary("font-scale", "1.3x"))))
        val contributorB = FakeOverrideSummaryContributor(mapOf(serialA to listOf(OverrideSummary("proxy", "10.0.0.1:8080"))))
        aggregator.registerOverrideSummaryContributor(contributorA)
        aggregator.registerOverrideSummaryContributor(contributorB)
        scope.runCurrent()
        val before = aggregator.state.value

        contributorA.overridesBySerial = mapOf(serialA to listOf(OverrideSummary("font-scale", "2.0x")))
        aggregator.refresh()
        scope.runCurrent()
        val after = aggregator.state.value

        before.overrides shouldBe listOf(OverrideSummary("font-scale", "1.3x"), OverrideSummary("proxy", "10.0.0.1:8080"))
        after.overrides shouldBe listOf(OverrideSummary("font-scale", "2.0x"), OverrideSummary("proxy", "10.0.0.1:8080"))
    }
}
