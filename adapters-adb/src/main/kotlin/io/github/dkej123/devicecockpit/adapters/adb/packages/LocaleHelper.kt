package io.github.dkej123.devicecockpit.adapters.adb.packages

import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.locale.DeviceLocalePort
import io.github.dkej123.devicecockpit.domain.locale.LocaleAction
import io.github.dkej123.devicecockpit.domain.locale.LocaleHelperCommand
import io.github.dkej123.devicecockpit.domain.locale.LocaleRead

/**
 * [DeviceLocalePort] backed by the device helper's `LocaleMain` entry point (task 060), pushed
 * through the same [deployment] as the app-info and dev-options helpers.
 */
class LocaleHelper(
    private val transport: AdbTransport,
    private val deployment: DeviceHelperDeployment,
) : DeviceLocalePort {

    override suspend fun run(serial: DeviceSerial, action: LocaleAction): LocaleRead {
        val remotePath = deployment.ensureDeployed(serial) ?: return LocaleRead.Failed("Device helper could not be installed")
        val result = LocaleHelperCommand.parse(transport.executeText(LocaleHelperCommand.request(serial, remotePath, action)))
        if (result is LocaleRead.Failed && result.reason == "Device helper did not start") deployment.forget(serial)
        return result
    }
}
