package io.github.dkej123.devicecockpit.application.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.deeplinks.AmStartOutput
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Copy and tones from design/DEEP_LINK_DIALOG.md §2b and §5 (handoff 7). */
class LaunchBannerTest {
    private val labels = mapOf("com.acme.shop" to "Acme Shop", "com.android.chrome" to "Chrome", "com.acme.debug" to "Acme Shop (debug)")
    private val label = { pkg: String -> labels[pkg] ?: pkg }
    private fun opened(output: String, matchCount: Int? = null, appLinkApp: String? = null) =
        DeviceLinkResult.Opened(AmStartOutput.parse(output), output, matchCount, appLinkApp)
    private fun banner(result: DeviceLinkResult, uri: String = "acme://p/1", pkg: String = "", coldStart: Boolean = false) =
        LaunchBanner.of(result, DeviceSerial.of("49060DLAQ002W7"), uri, pkg, coldStart, label)

    @Test
    fun `success names the app from Activity and the launch timing`() {
        banner(opened("Status: ok\nLaunchState: COLD\nActivity: com.acme.debug/.deeplink.DeepLinkActivity\nTotalTime: 583\nComplete"), pkg = "com.acme.debug") shouldBe
            LaunchBanner(BannerTone.Success, "Opened in Acme Shop (debug)", "Cold start · 583 ms", "com.acme.debug/.deeplink.DeepLinkActivity", "com.acme.debug")
        banner(opened("Status: ok\nLaunchState: WARM\nActivity: com.acme.shop/.Main\nTotalTime: 180\nComplete")).detail shouldBe
            "Warm start · 180 ms · Android picked this app"
        banner(opened("Status: ok\nLaunchState: UNKNOWN (0)\nActivity: com.acme.shop/.Main\nComplete"), pkg = "com.acme.shop").detail shouldBe ""
    }

    @Test
    fun `the browser taking an https link the app declares is a warning with the verify command`() {
        val result = opened("Status: ok\nActivity: com.android.chrome/com.google.android.apps.chrome.IntentDispatcher\nComplete", appLinkApp = "com.acme.shop")
        val banner = banner(result, uri = "https://shop.acme.com/cart")
        banner.tone shouldBe BannerTone.Warning
        banner.title shouldBe "Opened in Chrome, not in Acme Shop"
        banner.detail shouldBe "The App Link for shop.acme.com isn’t verified on this device, so the tap went to the browser."
        banner.action shouldBe BannerAction.CopyVerifyCommand("adb -s 49060DLAQ002W7 shell pm get-app-links com.acme.shop", "Copied: pm get-app-links com.acme.shop")
    }

    @Test
    fun `chooser, onNewIntent, no handler and adb error`() {
        val chooser = opened("Status: ok\nActivity: android/com.android.internal.app.ResolverActivity\nComplete", matchCount = 2)
        banner(chooser) shouldBe LaunchBanner(
            BannerTone.Warning, "The system chooser opened on the device",
            "2 apps handle this link. Pick one on the device, or choose the app in Open in.",
            "Activity: android/com.android.internal.app.ResolverActivity", null,
        )
        banner(chooser, uri = "https://shop.acme.com/x").detail shouldBe
            "The App Link for shop.acme.com isn’t verified, so Android asks which app to use. Pick one on the device."
        val delivered = opened("Warning: Activity not started, intent has been delivered to currently running top-most instance.\nStatus: ok\nActivity: com.acme.shop/.Main\nComplete")
        banner(delivered, pkg = "com.acme.shop") shouldBe LaunchBanner(
            BannerTone.Warning, "Delivered to the running activity",
            "Acme Shop was already on top and got the link in onNewIntent(). Nothing new was launched.",
            "com.acme.shop/.Main", "com.acme.shop", BannerAction.TurnOnColdStart,
        )
        banner(delivered, pkg = "com.acme.shop", coldStart = true).action shouldBe null
        val none = DeviceLinkResult.Failed("No app on this device handles this link", unresolved = true, raw = "Error: Activity not started, unable to resolve Intent { act=… }")
        banner(none, uri = "acme://p/1", pkg = "com.acme.shop") shouldBe LaunchBanner(
            BannerTone.Error, "No app on this device handles this link",
            "Acme Shop has no activity for this link with VIEW + BROWSABLE.",
            "Error: Activity not started, unable to resolve Intent", "com.acme.shop",
        )
        banner(none, uri = "acme://p/1").detail shouldBe
            "Nothing matches VIEW + BROWSABLE for acme://p. Check the scheme and host in the app’s intent filter."
        banner(DeviceLinkResult.Failed("error: device '49060DLAQ002W7' not found", raw = "error: device '49060DLAQ002W7' not found")) shouldBe LaunchBanner(
            BannerTone.Error, "Couldn’t open the link", "adb returned an error. Nothing was launched.", "error: device '49060DLAQ002W7' not found", null,
        )
    }

    @Test
    fun `handlers row copy for every state`() {
        val shop = "com.acme.shop/.deeplink.DeepLinkActivity"
        val debug = "com.acme.debug/.deeplink.DeepLinkActivity"
        val chrome = "com.android.chrome/com.google.android.apps.chrome.IntentDispatcher"
        HandlersRow.of(LinkHandlers.OpensDirectly(shop, listOf(shop)), "", label) shouldBe HandlersRow(BannerTone.Success, "Opens directly in", shop, null)
        HandlersRow.of(LinkHandlers.OpensDirectly(shop, listOf(shop, debug)), "", label).link shouldBe "All 2 matches ▾"
        HandlersRow.of(LinkHandlers.Chooser(listOf(shop, debug)), "", label) shouldBe
            HandlersRow(BannerTone.Warning, "Chooser appears · 2 apps handle this link", null, "Show apps ▾")
        HandlersRow.of(LinkHandlers.BrowserFallback(chrome, listOf(shop, chrome), "shop.acme.com"), "", label) shouldBe
            HandlersRow(BannerTone.Warning, "Opens in Chrome · the App Link for shop.acme.com isn’t verified", null, "Show 2 matches ▾")
        HandlersRow.of(LinkHandlers.HandledBy(debug, listOf(debug)), "com.acme.debug", label) shouldBe HandlersRow(BannerTone.Success, "Handled by", debug, null)
        HandlersRow.of(LinkHandlers.NotHandledBy("com.acme.shop", listOf(debug)), "com.acme.shop", label) shouldBe
            HandlersRow(BannerTone.Error, "Acme Shop doesn’t handle this link · 1 other app does", null, "Show apps ▾")
        HandlersRow.of(LinkHandlers.NotHandledBy("com.acme.shop", listOf(debug, chrome)), "com.acme.shop", label).text shouldBe
            "Acme Shop doesn’t handle this link · 2 other apps do"
        HandlersRow.of(LinkHandlers.Nothing, "", label) shouldBe HandlersRow(BannerTone.Error, "Nothing on this device handles this link", null, null)
    }
}
