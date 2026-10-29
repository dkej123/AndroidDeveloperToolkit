@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.dkej123.devicecockpit.application.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.*
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test

/** Device answers are `cmd package` output captured on a Samsung S23. */
class DeepLinkHandlersTest {
    private val serial = DeviceSerial.of("s")
    private class Memory : DeviceDeepLinkMemory {
        override var lastPackage = ""
        override fun entries() = emptyList<RecentDeviceLink>()
        override fun replace(entries: List<RecentDeviceLink>) {}
    }

    private fun match(vararg components: String) =
        if (components.isEmpty()) "No activities found"
        else "${components.size} activities found:\n" + components.withIndex().joinToString("\n") { (i, c) ->
            "  Activity #$i:\n    priority=0 preferredOrder=0 match=0x208000 specificIndex=-1 isDefault=true\n    $c"
        }
    private fun resolved(component: String?) =
        if (component == null) "No activity found" else "priority=0 preferredOrder=0 match=0x0 specificIndex=-1 isDefault=false\n$component"

    /** Answers by verb and URI; `https://example.invalid/` is the neutral link used to find the browsers. */
    private fun device(matches: Map<String, List<String>>, resolves: Map<String, String?>) = FakeAdbTransport(textScript = { request ->
        val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
        val uri = command.substringAfter("-d '").substringBeforeLast("'")
        val text = when {
            "query-activities" in command -> match(*matches[uri].orEmpty().toTypedArray())
            "resolve-activity" in command -> resolved(resolves[uri])
            else -> error(command)
        }
        AdbTextResult(AdbOutcome.Completed(null), text, "")
    })

    private val chrome = "com.android.chrome/com.google.android.apps.chrome.IntentDispatcher"
    private val browsers = mapOf("https://example.invalid/" to listOf(chrome))

    @Test
    fun `any app opening directly, through the chooser, or nothing`() = runTest {
        val gmail = "com.google.android.gm/.ComposeActivityGmailExternal"
        val outlook = "com.microsoft.office.outlook/.compose.ComposeLauncherActivity"
        val maps = "com.google.android.apps.maps/com.google.android.maps.MapsActivity"
        val waze = "com.waze/.FreeMapAppActivity"
        val useCase = DeviceDeepLinkUseCase(device(
            browsers + mapOf("mailto:a@b.c" to listOf(gmail, outlook), "geo:1,2" to listOf(maps, waze)),
            mapOf("mailto:a@b.c" to gmail, "geo:1,2" to "android/com.android.internal.app.ResolverActivity"),
        ), Memory()) { 0 }
        useCase.handlers(serial, "mailto:a@b.c", "") shouldBe LinkHandlers.OpensDirectly(gmail, listOf(gmail, outlook))
        useCase.handlers(serial, "geo:1,2", "") shouldBe LinkHandlers.Chooser(listOf(maps, waze))
        useCase.handlers(serial, "nosuch://x", "") shouldBe LinkHandlers.Nothing
    }

    @Test
    fun `an https link the app declares but the browser takes is an unverified App Link`() = runTest {
        val shop = "com.acme.shop/.deeplink.DeepLinkActivity"
        val uri = "https://shop.acme.com/cart"
        val useCase = DeviceDeepLinkUseCase(device(browsers + mapOf(uri to listOf(shop, chrome)), mapOf(uri to chrome)), Memory()) { 0 }
        useCase.handlers(serial, uri, "") shouldBe LinkHandlers.BrowserFallback(chrome, listOf(shop, chrome), "shop.acme.com")
        // A plain web page nobody else declares just opens in the browser.
        val web = DeviceDeepLinkUseCase(device(browsers + mapOf("https://example.com" to listOf(chrome)), mapOf("https://example.com" to chrome)), Memory()) { 0 }
        web.handlers(serial, "https://example.com", "") shouldBe LinkHandlers.OpensDirectly(chrome, listOf(chrome))
    }

    @Test
    fun `a specific app is checked against the full match list`() = runTest {
        val shop = "com.acme.shop/.deeplink.DeepLinkActivity"
        val debug = "com.acme.shop.debug/.deeplink.DeepLinkActivity"
        val useCase = DeviceDeepLinkUseCase(device(mapOf("acme://p" to listOf(shop, debug)), emptyMap()), Memory()) { 0 }
        useCase.handlers(serial, "acme://p", "com.acme.shop.debug") shouldBe LinkHandlers.HandledBy(debug, listOf(shop, debug))
        useCase.handlers(serial, "acme://p", "com.other") shouldBe LinkHandlers.NotHandledBy("com.other", listOf(shop, debug))
        useCase.handlers(serial, "none://p", "com.other") shouldBe LinkHandlers.Nothing
    }

    @Test
    fun `the handlers row waits 500 ms after the last edit, caches, drops stale answers and hides invalid input`() = runTest {
        val transport = device(mapOf("a://1" to listOf("com.a/.A"), "a://2" to listOf("com.b/.B")), mapOf("a://1" to "com.a/.A", "a://2" to "com.b/.B"))
        val controller = DeepLinkHandlersController(backgroundScope, DeviceDeepLinkUseCase(transport, Memory()) { 0 }, serial)
        controller.request("a://1", "")
        controller.state.value shouldBe HandlersState.Checking
        advanceTimeBy(499); runCurrent()
        transport.textRequests.size shouldBe 0
        controller.request("a://2", "")
        advanceTimeBy(501); runCurrent()
        controller.state.value shouldBe HandlersState.Ready(LinkHandlers.OpensDirectly("com.b/.B", listOf("com.b/.B")))
        transport.textRequests.size shouldBe 2
        controller.request("a://2", "")
        controller.state.value shouldBe HandlersState.Ready(LinkHandlers.OpensDirectly("com.b/.B", listOf("com.b/.B")))
        advanceTimeBy(1_000); runCurrent()
        transport.textRequests.size shouldBe 2
        controller.request("not a uri", "")
        controller.state.value shouldBe HandlersState.Hidden
        controller.refresh()
        controller.request("a://2", "")
        advanceTimeBy(501); runCurrent()
        transport.textRequests.size shouldBe 4
    }

    @Test
    fun `a failed query hides the row`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(1), "", "error: closed") })
        val controller = DeepLinkHandlersController(backgroundScope, DeviceDeepLinkUseCase(transport, Memory()) { 0 }, serial)
        controller.request("a://1", "")
        advanceTimeBy(501); runCurrent()
        controller.state.value shouldBe HandlersState.Hidden
    }
}
