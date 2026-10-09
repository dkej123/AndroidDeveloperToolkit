package io.github.dkej123.devicecockpit.adapters.adb.packages

import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.display.DisplaySettingRead
import io.github.dkej123.devicecockpit.domain.display.developer.ActivityManagerDebugPort
import io.github.dkej123.devicecockpit.domain.display.developer.DevOptionsAction
import io.github.dkej123.devicecockpit.domain.display.developer.DevOptionsHelperCommand

/**
 * [ActivityManagerDebugPort] backed by the device helper's `DevOptionsMain` entry point (ADR 0012),
 * pushed through the same [deployment] as the app-info helper.
 */
class DevOptionsHelper(
    private val transport: AdbTransport,
    private val deployment: DeviceHelperDeployment,
) : ActivityManagerDebugPort {

    override suspend fun run(serial: DeviceSerial, action: DevOptionsAction): DisplaySettingRead<Int> {
        val remotePath = deployment.ensureDeployed(serial)
            ?: return DisplaySettingRead.Malformed(raw = "", reason = "device helper could not be installed")
        val result = DevOptionsHelperCommand.parse(transport.executeText(DevOptionsHelperCommand.request(serial, remotePath, action)))
        if (result is DisplaySettingRead.Malformed) deployment.forget(serial)
        return result
    }
}
