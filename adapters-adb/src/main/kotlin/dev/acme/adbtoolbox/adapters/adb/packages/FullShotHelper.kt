package dev.acme.adbtoolbox.adapters.adb.packages

import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.capture.FullShotHelperCommand
import dev.acme.adbtoolbox.domain.capture.FullShotRender
import dev.acme.adbtoolbox.domain.capture.FullShotRenderer

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
