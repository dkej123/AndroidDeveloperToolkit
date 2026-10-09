package io.github.dkej123.devicecockpit.intellij.logcat

import io.github.dkej123.devicecockpit.application.logcat.LogcatControlsController
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.devicecontext.BadgeContributor
import io.github.dkej123.devicecockpit.domain.logcat.LogcatSessionState
import io.github.dkej123.devicecockpit.domain.nav.NavigationBadge
import io.github.dkej123.devicecockpit.domain.nav.ViewId

/**
 * Publishes the Logcat rail badge (task 037, `design/README.md` §2's rail badge dot: "red on Logcat
 * when errors are arriving"): an [NavigationBadge.Attention] dot while the currently selected
 * serial's Logcat session is in [LogcatSessionState.Error] — never for an expected
 * [LogcatSessionState.Stopped] (device unavailable/changed, requested stop, cancellation), mirroring
 * [io.github.dkej123.devicecockpit.application.network.NetworkBadgeContributor]'s exact-serial safety.
 */
class LogcatBadgeContributor(private val controller: LogcatControlsController) : BadgeContributor {

    override val viewId: ViewId = ViewId.Logcat

    override fun badgeFor(serial: DeviceSerial?): NavigationBadge {
        val state = controller.state.value
        if (state.serial != serial) return NavigationBadge.None
        return if (state.sessionState is LogcatSessionState.Error) NavigationBadge.Attention else NavigationBadge.None
    }
}
