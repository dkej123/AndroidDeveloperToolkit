package io.github.dkej123.devicecockpit.application.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial

enum class BannerTone { Success, Warning, Error }

sealed interface BannerAction {
    /** "Copy verify command": `pm get-app-links` for the app whose App Link the browser bypassed. */
    data class CopyVerifyCommand(val command: String, val toast: String) : BannerAction
    data object TurnOnColdStart : BannerAction
}

/** The in-dialog result of the last launch (design/DEEP_LINK_DIALOG.md §5, handoff 7). */
data class LaunchBanner(
    val tone: BannerTone,
    val title: String,
    val detail: String,
    val mono: String?,
    /** The app whose tile is shown, when one is known. */
    val appPackage: String?,
    val action: BannerAction? = null,
) {
    companion object {
        fun of(
            result: DeviceLinkResult,
            serial: DeviceSerial,
            uri: String,
            requestedPackage: String,
            coldStart: Boolean,
            label: (String) -> String,
        ): LaunchBanner = when (result) {
            is DeviceLinkResult.Failed -> failed(result, uri, requestedPackage, label)
            is DeviceLinkResult.Opened -> opened(result, serial, uri, requestedPackage, coldStart, label)
        }

        private fun failed(result: DeviceLinkResult.Failed, uri: String, requestedPackage: String, label: (String) -> String) =
            if (result.unresolved) {
                LaunchBanner(
                    BannerTone.Error, "No app on this device handles this link",
                    if (requestedPackage.isNotEmpty()) "${label(requestedPackage)} has no activity for this link with VIEW + BROWSABLE."
                    else "Nothing matches VIEW + BROWSABLE for ${schemeAndHost(uri)}. Check the scheme and host in the app’s intent filter.",
                    "Error: Activity not started, unable to resolve Intent", requestedPackage.ifEmpty { null },
                )
            } else {
                LaunchBanner(BannerTone.Error, "Couldn’t open the link", "adb returned an error. Nothing was launched.", result.message, null)
            }

        private fun opened(
            result: DeviceLinkResult.Opened,
            serial: DeviceSerial,
            uri: String,
            requestedPackage: String,
            coldStart: Boolean,
            label: (String) -> String,
        ): LaunchBanner {
            val output = result.output
            val handler = output.handlerPackage
            val component = output.activity
            return when {
                output.chooserShown -> LaunchBanner(
                    BannerTone.Warning, "The system chooser opened on the device",
                    if (isWebLink(uri)) "The App Link for ${hostOf(uri)} isn’t verified, so Android asks which app to use. Pick one on the device."
                    else "${result.matchCount ?: "Several"} apps handle this link. Pick one on the device, or choose the app in Open in.",
                    "Activity: $component", null,
                )
                output.deliveredToRunningInstance -> LaunchBanner(
                    BannerTone.Warning, "Delivered to the running activity",
                    "${label(handler ?: requestedPackage)} was already on top and got the link in onNewIntent(). Nothing new was launched.",
                    component, handler, BannerAction.TurnOnColdStart.takeUnless { coldStart },
                )
                handler != null && result.appLinkApp != null -> LaunchBanner(
                    BannerTone.Warning, "Opened in ${label(handler)}, not in ${label(result.appLinkApp)}",
                    "The App Link for ${hostOf(uri)} isn’t verified on this device, so the tap went to the browser.",
                    component, handler,
                    BannerAction.CopyVerifyCommand(
                        "adb -s ${serial.value} shell pm get-app-links ${result.appLinkApp}",
                        "Copied: pm get-app-links ${result.appLinkApp}",
                    ),
                )
                else -> LaunchBanner(
                    BannerTone.Success, if (handler != null) "Opened in ${label(handler)}" else "Opened",
                    listOfNotNull(
                        timing(output.launchState, output.totalTimeMs),
                        "Android picked this app".takeIf { requestedPackage.isEmpty() && handler != null },
                    ).joinToString(" · "),
                    component, handler,
                )
            }
        }

        private fun timing(state: String?, totalTimeMs: Long?): String? {
            val kind = when (state?.uppercase()) { "COLD" -> "Cold"; "WARM" -> "Warm"; "HOT" -> "Hot"; else -> return null }
            return if (totalTimeMs != null) "$kind start · $totalTimeMs ms" else "$kind start"
        }

        private fun schemeAndHost(uri: String): String {
            val scheme = uri.substringBefore(':')
            val host = hostOf(uri)
            return if (host.isNotEmpty()) "$scheme://$host" else "$scheme:"
        }
    }
}

/** The "Who handles this link?" row (§2b). [handler] is the component shown with its tile; [link] opens the popover. */
data class HandlersRow(val tone: BannerTone, val text: String, val handler: String?, val link: String?) {
    companion object {
        fun of(handlers: LinkHandlers, selectedPackage: String, label: (String) -> String): HandlersRow {
            val count = handlers.matches.size
            val all = if (count > 1) "All $count matches ▾" else null
            return when (handlers) {
                is LinkHandlers.OpensDirectly -> HandlersRow(BannerTone.Success, "Opens directly in", handlers.handler, all)
                is LinkHandlers.Chooser -> HandlersRow(BannerTone.Warning, "Chooser appears · $count apps handle this link", null, "Show apps ▾")
                is LinkHandlers.BrowserFallback -> HandlersRow(
                    BannerTone.Warning,
                    "Opens in ${label(packageOf(handlers.browser))} · the App Link for ${handlers.host} isn’t verified",
                    null, "Show $count matches ▾",
                )
                is LinkHandlers.HandledBy -> HandlersRow(BannerTone.Success, "Handled by", handlers.handler, all)
                is LinkHandlers.NotHandledBy -> HandlersRow(
                    BannerTone.Error,
                    "${label(selectedPackage.ifEmpty { handlers.packageName })} doesn’t handle this link · $count other " +
                        (if (count == 1) "app does" else "apps do"),
                    null, "Show apps ▾",
                )
                LinkHandlers.Nothing -> HandlersRow(BannerTone.Error, "Nothing on this device handles this link", null, null)
            }
        }
    }
}
