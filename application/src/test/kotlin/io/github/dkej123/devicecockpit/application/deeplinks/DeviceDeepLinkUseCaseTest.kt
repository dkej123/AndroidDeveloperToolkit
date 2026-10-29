package io.github.dkej123.devicecockpit.application.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.*
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

private class Memory : DeviceDeepLinkMemory {
    override var lastPackage = ""
    private var saved = emptyList<RecentDeviceLink>()
    override fun entries() = saved
    override fun replace(entries: List<RecentDeviceLink>) { saved = entries }
}

class DeviceDeepLinkUseCaseTest {
    private val serial = DeviceSerial.of("wifi:5555")
    private fun ok(text: String) = AdbTextResult(AdbOutcome.Completed(0), text, "")

    @Test
    fun `command preview keeps arguments on separate lines and shows the empty URI template`() {
        val useCase = DeviceDeepLinkUseCase(FakeAdbTransport(), Memory()) { 0 }
        useCase.previewCommand(serial, "", "").lines().size shouldBe 5
        useCase.previewCommand(serial, "", "").lines().first() shouldBe "adb -s wifi:5555 shell am start \\"
        useCase.previewCommand(serial, "", "").lines().last() shouldBe "    -d '<uri>'"
        val preview = useCase.previewCommand(serial, "test://item?x=O'Reilly&y=2", "com.example")
        preview.lines().size shouldBe 6
        preview.lines().last() shouldBe "    -p com.example"
        preview.contains("O'\\''Reilly") shouldBe true
        useCase.terminalCommand(serial, "test://item?x=1&y=2", "").startsWith("adb -s 'wifi:5555' shell 'am start") shouldBe true
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an unresponsive launch times out so the dialog can be used again`() = runTest {
        val useCase = DeviceDeepLinkUseCase(object : AdbTransport by FakeAdbTransport() {
            override suspend fun executeText(request: AdbRequest): AdbTextResult = kotlinx.coroutines.awaitCancellation()
        }, Memory()) { 0 }
        var result: DeviceLinkResult? = null
        val job = launch { result = useCase.open(serial, "geo:52.2,21.0", "") }
        advanceTimeBy(15_001)
        runCurrent()
        (result is DeviceLinkResult.Failed) shouldBe true
        job.isCompleted shouldBe true
        useCase.history() shouldBe emptyList()
    }

    @Test
    fun `package choices include all user apps and known system apps sorted by label`() = runTest {
        val transport = FakeAdbTransport(textScript = { ok("package:com.new\npackage:com.known\n") })
        val useCase = DeviceDeepLinkUseCase(transport, Memory()) { 0 }
        val known = listOf(
            io.github.dkej123.devicecockpit.domain.packages.PackageEntry.unresolved("com.known").copy(label = "Alpha"),
            io.github.dkej123.devicecockpit.domain.packages.PackageEntry.unresolved("com.system").copy(label = "System"),
        )
        useCase.packages(serial, known).map { it.packageName } shouldBe listOf("com.known", "com.new", "com.system")
        val request = transport.textRequests.single() as AdbDeviceRequest
        (request.operation as AdbOperation.Shell).command.render() shouldBe "pm list packages -3"
        request.serial shouldBe serial
    }

    /** ddmlib (the transport inside Android Studio) completes shell calls without an exit code. */
    private fun ddmlib(text: String) = AdbTextResult(AdbOutcome.Completed(exitCode = null), text, "")

    @Test
    fun `package choices load through ddmlib which reports no exit code`() = runTest {
        val useCase = DeviceDeepLinkUseCase(FakeAdbTransport(textScript = { ddmlib("package:com.acme\n") }), Memory()) { 0 }
        useCase.packages(serial, emptyList()).map { it.packageName } shouldBe listOf("com.acme")
    }

    @Test
    fun `a launch through ddmlib with Status ok opens and is saved to history`() = runTest {
        val output = "Starting: Intent { act=android.intent.action.VIEW }\nStatus: ok\nLaunchState: WARM\nComplete"
        val memory = Memory()
        val useCase = DeviceDeepLinkUseCase(FakeAdbTransport(textScript = { ddmlib(output) }), memory) { 7 }
        (useCase.open(serial, "https://example.com/?a=1&b=2", "com.android.chrome") is DeviceLinkResult.Opened) shouldBe true
        useCase.history() shouldBe listOf(RecentDeviceLink("https://example.com/?a=1&b=2", "com.android.chrome", 7))
        memory.lastPackage shouldBe "com.android.chrome"
    }

    @Test
    fun `an unresolved intent through ddmlib is reported from merged output`() = runTest {
        val output = "Starting: Intent { act=android.intent.action.VIEW }\nError: Activity not started, unable to resolve Intent { act=android.intent.action.VIEW }"
        val useCase = DeviceDeepLinkUseCase(FakeAdbTransport(textScript = { ddmlib(output) }), Memory()) { 0 }
        (useCase.open(serial, "nosuchscheme://x", "") as DeviceLinkResult.Failed).unresolved shouldBe true
        useCase.history() shouldBe emptyList()
    }

    @Test
    fun `an unsuccessful am status is not saved even when the shell exits zero`() = runTest {
        val useCase = DeviceDeepLinkUseCase(FakeAdbTransport(textScript = { ok("Starting: Intent {}\nStatus: error") }), Memory()) { 0 }
        (useCase.open(serial, "test://item", "") is DeviceLinkResult.Failed) shouldBe true
        useCase.history() shouldBe emptyList()
    }

    @Test
    fun `successful launches keep fifty unique URI and package pairs newest first`() = runTest {
        val transport = FakeAdbTransport(textScript = { ok("Starting: Intent { act=android.intent.action.VIEW }\nStatus: ok\nComplete") })
        val memory = Memory()
        var clock = 100L
        val useCase = DeviceDeepLinkUseCase(transport, memory) { clock++ }
        repeat(51) { (useCase.open(serial, "test://item/$it", "") is DeviceLinkResult.Opened) shouldBe true }
        useCase.history().size shouldBe 50
        useCase.history().last().uri shouldBe "test://item/1"
        useCase.open(serial, "test://item/10", "com.example")
        useCase.open(serial, "test://item/10", "")
        useCase.history().take(2).map { it.packageName } shouldBe listOf("", "com.example")
        useCase.history().first().lastRun shouldBe 152L
        transport.textRequests.filterIsInstance<AdbDeviceRequest>().all { it.serial == serial } shouldBe true
        DeviceDeepLinkUseCase(transport, memory) { 0 }.history() shouldBe useCase.history()
        useCase.remove(useCase.history().first())
        useCase.history().none { it.uri == "test://item/10" && it.packageName.isEmpty() } shouldBe true
    }

    @Test
    fun `remember the last successful package but never record failed launches`() = runTest {
        val memory = Memory()
        var response = ok("Status: ok")
        val useCase = DeviceDeepLinkUseCase(FakeAdbTransport(textScript = { response }), memory) { 123 }
        (useCase.open(serial, "test://item", "com.example") is DeviceLinkResult.Opened) shouldBe true
        useCase.rememberedPackage() shouldBe "com.example"
        response = ok("Error: Activity not started, unable to resolve Intent { dat=test://other }")
        (useCase.open(serial, "test://other", "") as DeviceLinkResult.Failed).unresolved shouldBe true
        response = AdbTextResult(AdbOutcome.Completed(1), "", "Permission denied\nmore details")
        (useCase.open(serial, "test://other", "") as DeviceLinkResult.Failed).message shouldBe "Permission denied"
        response = AdbTextResult(AdbOutcome.TimedOut, "", "")
        (useCase.open(serial, "test://other", "") is DeviceLinkResult.Failed) shouldBe true
        useCase.history() shouldBe listOf(RecentDeviceLink("test://item", "com.example", 123))
        memory.lastPackage shouldBe "com.example"
    }

    @Test
    fun `invalid input never reaches adb and preview is a copyable command`() = runTest {
        val transport = FakeAdbTransport()
        val useCase = DeviceDeepLinkUseCase(transport, Memory()) { 0 }
        (useCase.open(serial, "no scheme", "") is DeviceLinkResult.Failed) shouldBe true
        transport.textRequests shouldBe emptyList()
        useCase.previewLine(serial, "test://item?a=1&b=2", "").contains("-d 'test://item?a=1&b=2'") shouldBe true
    }

    @Test
    fun `the result names the app that really took the link, not the one that was requested`() = runTest {
        val output = "Status: ok\nLaunchState: WARM\nActivity: com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity\nComplete"
        val useCase = DeviceDeepLinkUseCase(FakeAdbTransport(textScript = { ok(output) }), Memory()) { 0 }
        (useCase.open(serial, "https://example.com", "") as DeviceLinkResult.Opened).output.handlerPackage shouldBe "com.android.chrome"
    }

    @Test
    fun `browser intent links are refused before adb with an explanation`() = runTest {
        val transport = FakeAdbTransport(textScript = { ok("Status: ok") })
        val useCase = DeviceDeepLinkUseCase(transport, Memory()) { 0 }
        (useCase.open(serial, "intent://scan/#Intent;scheme=zxing;end", "") as DeviceLinkResult.Failed).message shouldBe DeviceDeepLinkUseCase.INTENT_SCHEME_MESSAGE
        transport.textRequests shouldBe emptyList()
    }

    @Test
    fun `cold start reaches the launch and the preview, collapsed to one line`() = runTest {
        val transport = FakeAdbTransport(textScript = { ok("Status: ok") })
        val useCase = DeviceDeepLinkUseCase(transport, Memory()) { 0 }
        useCase.open(serial, "a://b", "com.acme", coldStart = true)
        ((transport.textRequests.single() as AdbDeviceRequest).operation as AdbOperation.Shell).command.render().contains(" -S ") shouldBe true
        useCase.previewCommand(serial, "a://b", "com.acme", coldStart = true).lines()[2] shouldBe "    -S \\"
        useCase.previewLine(serial, "a://b", "com.acme", coldStart = true) shouldBe
            "adb -s wifi:5555 shell am start -W -S -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d 'a://b' -p com.acme"
        useCase.terminalCommand(serial, "a://b", "", coldStart = true).contains("am start -W -S ") shouldBe true
    }

    @Test
    fun `when the browser took an https link the app also declares, the result names that app`() = runTest {
        val chrome = "com.android.chrome/com.google.android.apps.chrome.IntentDispatcher"
        val transport = FakeAdbTransport(textScript = { request ->
            val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
            when {
                command.startsWith("am start") -> ok("Status: ok\nActivity: $chrome\nComplete")
                "example.invalid" in command -> ok("1 activities found:\n  Activity #0:\n    $chrome")
                else -> ok("2 activities found:\n  Activity #0:\n    com.acme.shop/.Deep\n  Activity #1:\n    $chrome")
            }
        })
        val result = DeviceDeepLinkUseCase(transport, Memory()) { 0 }.open(serial, "https://shop.acme.com/cart", "") as DeviceLinkResult.Opened
        result.appLinkApp shouldBe "com.acme.shop"
        result.matchCount shouldBe null // only the chooser banner counts the matches
    }

    @Test
    fun `history remembers which app really took an Any app link, but not the chooser`() = runTest {
        var output = "Status: ok\nActivity: com.cleevio.ionity/.app.ui.DeepLinkActivity\nComplete"
        val useCase = DeviceDeepLinkUseCase(FakeAdbTransport(textScript = { ok(output) }), Memory()) { 9 }
        useCase.open(serial, "ionitystage://map", "")
        useCase.history().first() shouldBe RecentDeviceLink("ionitystage://map", "", 9, handledBy = "com.cleevio.ionity")
        output = "Status: ok\nActivity: android/com.android.internal.app.ResolverActivity\nComplete"
        useCase.open(serial, "geo:1,2", "")
        useCase.history().first().handledBy shouldBe ""
        // Re-running the same link keeps one entry and updates who handled it.
        output = "Status: ok\nActivity: com.other/.Main\nComplete"
        useCase.open(serial, "ionitystage://map", "")
        useCase.history().count { it.uri == "ionitystage://map" } shouldBe 1
        useCase.history().first().handledBy shouldBe "com.other"
    }
}
