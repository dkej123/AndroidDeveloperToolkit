@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.dkej123.devicecockpit.application.currentapp

import io.github.dkej123.devicecockpit.application.apps.AppLifecycleUseCase
import io.github.dkej123.devicecockpit.application.apps.ClearDataUseCase
import io.github.dkej123.devicecockpit.application.apps.UninstallUseCase
import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.device.Device
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("emulator-5554")

private class Dispatchers(d: CoroutineDispatcher) : DispatcherProvider {
    override val default = d
    override val io = d
    override val main = d
}

/** The device: what is in front, whether its process runs, and every command seen. */
private class Phone(var front: String = "com.acme.shop", var running: Boolean = true) {
    val commands = mutableListOf<String>()
    val granted = mutableListOf("android.permission.CAMERA")
    val requested get() = (listOf("android.permission.CAMERA", "android.permission.ACCESS_COARSE_LOCATION") + granted).distinct()
    var queries = 0

    fun transport() = FakeAdbTransport(textScript = { request ->
        val op = (request as AdbDeviceRequest).operation
        val line = (op as? AdbOperation.Shell)?.command?.render() ?: (op as AdbOperation.Host).arguments.joinToString(" ")
        commands += line
        fun ok(out: String = "") = AdbTextResult(AdbOutcome.Completed(0), out, "")
        when {
            line.startsWith("cmd package resolve-activity") -> ok("com.launcher/.Home")
            line.startsWith("dumpsys activity activities") -> {
                queries++
                val component = if (front == "home") "com.launcher/.Home" else "$front/.Main"
                ok("  topResumedActivity=ActivityRecord{1 u0 $component t1}\n--adbtoolbox-window--\n")
            }
            line.startsWith("dumpsys package") -> {
                val pkg = line.substringAfter("dumpsys package ").trim('\'')
                ok("Packages:\n  Package [$pkg] (1):\n    versionCode=1 minSdk=26 targetSdk=36\n    versionName=1.0\n    runtime permissions:\n" +
                    requested.joinToString("") { "      $it: granted=${it in granted}\n" })
            }
            line.startsWith("pm grant") -> { granted += line.substringAfterLast(' ').trim('\''); ok() }
            // Revoking a runtime permission kills the app; the phone shows the launcher again.
            line.startsWith("pm revoke") -> { granted -= line.substringAfterLast(' ').trim('\''); running = false; front = "home"; ok() }
            line.startsWith("pidof") -> if (running) ok("4242") else AdbTextResult(AdbOutcome.Completed(1), "", "")
            line.startsWith("ps -o") -> ok("00:05")
            line.startsWith("am force-stop") -> { running = false; front = "home"; ok() }
            line.startsWith("input keyevent KEYCODE_HOME") -> { front = "home"; ok() }
            line.startsWith("am kill") -> { running = false; ok() }
            "monkey -p" in line -> { running = true; front = line.substringAfter("-p '").substringBefore("'"); ok("Events injected: 1") }
            line.startsWith("pm uninstall") || line.startsWith("uninstall") -> { front = "home"; ok("Success") }
            else -> ok("Success")
        }
    })
}

class CurrentAppViewModelTest {
    private val scope = TestScope()
    private val phone = Phone()
    private val transport = phone.transport()
    private val events = mutableListOf<CurrentAppEvent>()
    private val selected = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(Device(serial, DeviceConnectionState.Online)))
    private val vm = CurrentAppViewModel(
        scope, Dispatchers(StandardTestDispatcher(scope.testScheduler)), CurrentAppUseCase(transport),
        AppLifecycleUseCase(transport), ClearDataUseCase(transport), UninstallUseCase(transport), selected,
        now = { scope.testScheduler.currentTime }, onEvent = { events += it },
    )

    private fun shownPackage() = (vm.state.value.display as? CurrentAppDisplay.App)?.app?.packageName

    @Test
    fun `selecting a device reads the app in front`() {
        vm.state.value.display shouldBe CurrentAppDisplay.Reading
        scope.runCurrent()
        shownPackage() shouldBe "com.acme.shop"
        (vm.state.value.display as CurrentAppDisplay.App).snapshot.process?.pid shouldBe 4242
    }

    @Test
    fun `polls every 3 s only while visible and focused`() {
        scope.runCurrent()
        val before = phone.queries
        scope.testScheduler.advanceTimeBy(10_000)
        phone.queries shouldBe before // not visible

        vm.setVisible(true)
        scope.runCurrent()
        val shown = phone.queries
        scope.testScheduler.advanceTimeBy(9_100)
        scope.runCurrent()
        phone.queries shouldBe shown + 3

        vm.setWindowFocused(false)
        scope.testScheduler.advanceTimeBy(9_000)
        phone.queries shouldBe shown + 3
    }

    @Test
    fun `a change under the pointer is held and announced, then applied on leaving`() {
        scope.runCurrent()
        vm.setPointerInside(true)
        phone.front = "com.maps"

        vm.setVisible(true)
        scope.runCurrent()

        shownPackage() shouldBe "com.acme.shop"
        vm.state.value.pendingPackage shouldBe "com.maps"
        vm.setPointerInside(false)
        shownPackage() shouldBe "com.maps"
        vm.state.value.pendingPackage shouldBe null
    }

    @Test
    fun `a pointer resting over the section stops holding after 2 s, a moving one keeps holding`() {
        vm.setVisible(true)
        scope.runCurrent()
        vm.setPointerInside(true)
        phone.front = "com.maps"

        scope.testScheduler.advanceTimeBy(2_500)
        vm.pointerMoved()
        scope.testScheduler.advanceTimeBy(600)
        scope.runCurrent()
        shownPackage() shouldBe "com.acme.shop" // poll at 3 s, pointer moved 0.5 s ago
        vm.state.value.pendingPackage shouldBe "com.maps"

        scope.testScheduler.advanceTimeBy(3_000)
        scope.runCurrent()
        shownPackage() shouldBe "com.maps" // poll at 6 s, pointer still for 3.5 s
        vm.state.value.pendingPackage shouldBe null
    }

    @Test
    fun `a manual refresh never holds`() {
        scope.runCurrent()
        vm.setPointerInside(true)
        phone.front = "com.maps"
        vm.refreshNow()
        scope.runCurrent()
        shownPackage() shouldBe "com.maps"
    }

    @Test
    fun `kill keeps the app shown as not running, launch brings it back`() {
        scope.runCurrent()
        vm.perform(CurrentAppAction.Kill, "com.acme.shop")
        scope.advanceUntilIdle()

        val killed = vm.state.value.display.shouldBeInstanceOf<CurrentAppDisplay.App>()
        killed.app.packageName shouldBe "com.acme.shop"
        killed.killed shouldBe true
        killed.snapshot.process shouldBe null
        events.single() shouldBe CurrentAppEvent.Done("Force-stopped com.acme.shop")

        vm.perform(CurrentAppAction.Launch, "com.acme.shop")
        scope.advanceUntilIdle()
        (vm.state.value.display as CurrentAppDisplay.App).killed shouldBe false
    }

    @Test
    fun `process death kills the app in the background and brings it back`() {
        scope.runCurrent()
        vm.perform(CurrentAppAction.ProcessDeath, "com.acme.shop")
        scope.advanceUntilIdle()

        phone.commands.filter { it.startsWith("input") || it.startsWith("am kill") || "monkey -p" in it } shouldBe listOf(
            "input keyevent KEYCODE_HOME",
            "am kill 'com.acme.shop'",
            io.github.dkej123.devicecockpit.domain.apps.AppLifecycleCommands.launch("com.acme.shop").render(),
        )
        events.single() shouldBe CurrentAppEvent.Done("com.acme.shop was killed in the background and reopened from its saved state")
        shownPackage() shouldBe "com.acme.shop"
    }

    @Test
    fun `after uninstall the section shows the home screen without a last app`() {
        scope.runCurrent()
        vm.perform(CurrentAppAction.Uninstall, "com.acme.shop")
        scope.advanceUntilIdle()
        vm.state.value.display shouldBe CurrentAppDisplay.Home("com.launcher", lastApp = null)
    }

    @Test
    fun `the home screen remembers the last app`() {
        scope.runCurrent()
        phone.front = "home"
        vm.refreshNow()
        scope.runCurrent()
        vm.state.value.display shouldBe CurrentAppDisplay.Home("com.launcher", lastApp = "com.acme.shop")
    }

    @Test
    fun `one permission can be reset on its own`() {
        scope.runCurrent()
        vm.resetPermission("com.acme.shop", "android.permission.CAMERA")
        scope.advanceUntilIdle()

        phone.commands.filter { it.startsWith("pm revoke") } shouldBe listOf("pm revoke --user 0 'com.acme.shop' 'android.permission.CAMERA'")
        events.single() shouldBe CurrentAppEvent.Done("CAMERA reset for com.acme.shop")
    }

    /**
     * Permissions granted while the same app stays in front showed only after switching apps, even after Refresh
     * (user report). A manual refresh re-reads them, and it reports "done" only once the new list is shown — the
     * Reset permissions menu opens on that signal.
     */
    @Test
    fun `refresh re-reads permissions and finishes only with the new list shown`() {
        scope.runCurrent()
        phone.granted += "android.permission.ACCESS_FINE_LOCATION"
        val seen = mutableListOf<CurrentAppViewState>()
        val collector = scope.backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect { seen += it } }
        vm.refreshNow()
        scope.runCurrent()
        collector.cancel()
        fun permissions(state: CurrentAppViewState) =
            (state.display as? CurrentAppDisplay.App)?.snapshot?.details?.runtimePermissions?.filter { it.granted }?.map { it.name }.orEmpty()
        permissions(vm.state.value) shouldBe listOf("android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION")
        val started = seen.indexOfFirst { it.refreshing }
        (started >= 0) shouldBe true
        seen.drop(started).filter { !it.refreshing }.forEach { permissions(it).size shouldBe 2 }
    }

    /** Reset of one permission kills the app; reopening the menu still said "granted" (user report, 2026-10-29). */
    @Test
    fun `after resetting one permission the stopped app shows it as denied`() {
        phone.granted += "android.permission.ACCESS_COARSE_LOCATION"
        scope.runCurrent()
        vm.resetPermission("com.acme.shop", "android.permission.ACCESS_COARSE_LOCATION")
        scope.testScheduler.advanceTimeBy(5_000); scope.runCurrent()
        vm.refreshNow(); scope.runCurrent()
        val display = vm.state.value.display as CurrentAppDisplay.App
        display.killed shouldBe true
        display.snapshot.details?.runtimePermissions?.associate { it.name to it.granted } shouldBe
            mapOf("android.permission.CAMERA" to true, "android.permission.ACCESS_COARSE_LOCATION" to false)
    }

    /** Denied permissions can be granted from the same menu, without opening App details (user request, 2026-10-29). */
    @Test
    fun `granting a denied permission from Current app shows it granted`() {
        scope.runCurrent()
        vm.grantPermission("com.acme.shop", "android.permission.ACCESS_COARSE_LOCATION")
        scope.testScheduler.advanceTimeBy(5_000); scope.runCurrent()
        phone.commands.any { it.startsWith("pm grant") && it.endsWith("'android.permission.ACCESS_COARSE_LOCATION'") } shouldBe true
        val display = vm.state.value.display as CurrentAppDisplay.App
        display.snapshot.details?.runtimePermissions?.first { it.name == "android.permission.ACCESS_COARSE_LOCATION" }?.granted shouldBe true
        events.last() shouldBe CurrentAppEvent.Done("Granted Access coarse location to com.acme.shop")
    }
}
