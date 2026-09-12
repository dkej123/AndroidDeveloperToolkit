package dev.acme.adbtoolbox.application.network

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummary
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.network.ProxyReadState

/**
 * Publishes the active global-proxy endpoint as a per-serial override (task 014), reading only
 * [ProxyController.state]'s current, readback-derived value — never the last requested endpoint.
 */
class ProxyOverrideSummaryContributor(private val controller: ProxyController) : OverrideSummaryContributor {

    override fun overridesFor(serial: DeviceSerial?): List<OverrideSummary> {
        val state = controller.state.value
        if (state.serial != serial) return emptyList()
        val readState = state.readState
        return if (readState is ProxyReadState.Active) {
            listOf(OverrideSummary(id = "proxy", description = "Proxy: ${readState.endpoint.render()}"))
        } else {
            emptyList()
        }
    }
}
