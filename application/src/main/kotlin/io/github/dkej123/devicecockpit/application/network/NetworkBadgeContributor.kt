package io.github.dkej123.devicecockpit.application.network

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.devicecontext.BadgeContributor
import io.github.dkej123.devicecockpit.domain.nav.NavigationBadge
import io.github.dkej123.devicecockpit.domain.nav.ViewId
import io.github.dkej123.devicecockpit.domain.network.ProxyReadState

/**
 * Publishes the Network rail badge (task 032, `design/README.md` §2's rail badge dot): an
 * [NavigationBadge.Attention] dot while the currently selected serial has an active global proxy,
 * reading only [ProxyController.state]'s current, readback-derived value — never the last requested
 * endpoint, mirroring [ProxyOverrideSummaryContributor]'s exact-serial safety.
 */
class NetworkBadgeContributor(private val controller: ProxyController) : BadgeContributor {

    override val viewId: ViewId = ViewId.Network

    override fun badgeFor(serial: DeviceSerial?): NavigationBadge {
        val state = controller.state.value
        if (state.serial != serial) return NavigationBadge.None
        return if (state.readState is ProxyReadState.Active) NavigationBadge.Attention else NavigationBadge.None
    }
}
