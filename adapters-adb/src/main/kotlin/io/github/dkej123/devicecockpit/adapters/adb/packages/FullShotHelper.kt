package io.github.dkej123.devicecockpit.adapters.adb.packages

import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.capture.FullShotHelperCommand
import io.github.dkej123.devicecockpit.domain.capture.FullShotRender
import io.github.dkej123.devicecockpit.domain.capture.FullShotRenderer

/**
 * [FullShotRenderer] backed by the device helper's `FullShotMain` entry point (ADR 0013), pushed
 * through the same [deployment] as the app-info and dev-options helpers.
 */
class FullShotHelper(
    private val transport: AdbTransport,
    private val deployment: DeviceHelperDeployment,
) : FullShotRenderer {

    override suspend fun render(serial: DeviceSerial): FullShotRender {
        val remotePath = deployment.ensureDeployed(serial)
            ?: return FullShotRender.Failed("Device helper could not be installed", helperStarted = false)
        val result = FullShotHelperCommand.parse(transport.executeText(FullShotHelperCommand.request(serial, remotePath)))
        if (result is FullShotRender.Failed && !result.helperStarted) deployment.forget(serial)
        return result
    }
}
