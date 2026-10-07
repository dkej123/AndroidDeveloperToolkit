@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.currentapp

import dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase
import dev.acme.adbtoolbox.application.apps.ClearDataUseCase
import dev.acme.adbtoolbox.application.apps.UninstallUseCase
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
            line.startsWith("dumpsys package") -> ok("Packages:\n  Package [$front] (1):\n    versionCode=1 minSdk=26 targetSdk=36\n    versionName=1.0\n")
            line.startsWith("pidof") -> if (running) ok("4242") else AdbTextResult(AdbOutcome.Completed(1), "", "")
            line.startsWith("ps -o") -> ok("00:05")
            line.startsWith("am force-stop") -> { running = false; front = "home"; ok() }
            line.startsWith("monkey") -> { running = true; front = line.substringAfter("-p '").substringBefore("'"); ok("Events injected: 1") }
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
}
