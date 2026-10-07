package dev.acme.adbtoolbox.adapters.adb.packages

import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.locale.DeviceLocalePort
import dev.acme.adbtoolbox.domain.locale.LocaleAction
import dev.acme.adbtoolbox.domain.locale.LocaleHelperCommand
import dev.acme.adbtoolbox.domain.locale.LocaleRead

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
