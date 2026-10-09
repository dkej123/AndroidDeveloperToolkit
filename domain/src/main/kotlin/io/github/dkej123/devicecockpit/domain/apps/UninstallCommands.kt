package io.github.dkej123.devicecockpit.domain.apps

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation

/** Exact host-side uninstall command from `design/IMPLEMENTATION.md` §4. */
object UninstallCommands {
    fun uninstall(packageName: String): AdbOperation.Host =
        AdbOperation.Host(listOf("uninstall", packageName))
}
