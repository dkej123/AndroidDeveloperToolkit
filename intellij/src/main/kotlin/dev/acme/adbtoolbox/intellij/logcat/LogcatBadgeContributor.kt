package dev.acme.adbtoolbox.intellij.logcat

import dev.acme.adbtoolbox.application.logcat.LogcatControlsController
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.BadgeContributor
import dev.acme.adbtoolbox.domain.logcat.LogcatSessionState
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId

/**
 * Publishes the Logcat rail badge (task 037, `design/README.md` §2's rail badge dot: "red on Logcat
 * when errors are arriving"): an [NavigationBadge.Attention] dot while the currently selected
 * serial's Logcat session is in [LogcatSessionState.Error] — never for an expected
 * [LogcatSessionState.Stopped] (device unavailable/changed, requested stop, cancellation), mirroring
 * [dev.acme.adbtoolbox.application.network.NetworkBadgeContributor]'s exact-serial safety.
 */
class LogcatBadgeContributor(private val controller: LogcatControlsController) : BadgeContributor {

    override val viewId: ViewId = ViewId.Logcat

    override fun badgeFor(serial: DeviceSerial?): NavigationBadge {
        val state = controller.state.value
        if (state.serial != serial) return NavigationBadge.None
        return if (state.sessionState is LogcatSessionState.Error) NavigationBadge.Attention else NavigationBadge.None
    }
}
