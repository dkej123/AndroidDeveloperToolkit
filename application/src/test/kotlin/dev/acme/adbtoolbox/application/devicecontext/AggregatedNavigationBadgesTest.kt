package dev.acme.adbtoolbox.application.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [AggregatedNavigationBadges] connects task 014's pull-based [BadgeContributor] registration to
 * task 012's [dev.acme.adbtoolbox.domain.nav.NavigationBadges] contract the rail already consumes —
 * reusing [NavigationBadge] as the value vocabulary rather than inventing a second badge shape.
 */
class AggregatedNavigationBadgesTest {

    @Test
    fun `exposes the aggregator's current badge map through the NavigationBadges contract`() = runTest {
        val scope = TestScope()
        val serial = DeviceSerial.of("AAAA111")
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(
            SelectedDeviceState.Online(Device(serial = serial, state = DeviceConnectionState.Online)),
        )
        val aggregator = DeviceContextAggregator(scope = scope, selectedDeviceState = selectedDeviceState)
        val badges = AggregatedNavigationBadges(scope, aggregator)

        aggregator.registerBadgeContributor(FakeBadgeContributor(ViewId.Logcat, mapOf(serial to NavigationBadge.Attention)))
        scope.runCurrent()

        badges.state.value shouldBe mapOf(ViewId.Logcat to NavigationBadge.Attention)
    }
}
