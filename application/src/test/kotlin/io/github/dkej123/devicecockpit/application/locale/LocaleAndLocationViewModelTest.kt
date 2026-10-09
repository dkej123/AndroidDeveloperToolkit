@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.dkej123.devicecockpit.application.locale

import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.device.Device
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.devicecontext.OverrideResetOutcome
import io.github.dkej123.devicecockpit.domain.devicecontext.OverrideSummary
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.locale.DeviceLocalePort
import io.github.dkej123.devicecockpit.domain.locale.LocaleAction
import io.github.dkej123.devicecockpit.domain.locale.LocaleRead
import io.github.dkej123.devicecockpit.domain.location.GeoPoint
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val emulator = DeviceSerial.of("emulator-5554")
private val phone = DeviceSerial.of("R58N90ABCDE")
private fun online(serial: DeviceSerial) = SelectedDeviceState.Online(Device(serial, DeviceConnectionState.Online))

private class TestDispatchers(d: CoroutineDispatcher) : DispatcherProvider {
    override val default = d
    override val io = d
    override val main = d
}

class LocaleViewModelTest {
    private var locale = "en-US"
    private val port = object : DeviceLocalePort {
        override suspend fun run(serial: DeviceSerial, action: LocaleAction): LocaleRead {
            if (action is LocaleAction.Set) locale = action.locales.first().value
            return LocaleRead.Locales(listOf(locale))
        }
    }
    private val scope = TestScope()
    private val events = mutableListOf<LocaleEvent>()
    private val selected = MutableStateFlow<SelectedDeviceState>(online(emulator))
    private val vm = LocaleViewModel(scope, TestDispatchers(StandardTestDispatcher(scope.testScheduler)), DeviceLocaleUseCase(port, InMemoryOriginalLocaleStore()), selected) { events += it }

    @Test
    fun `reads the locale, applies one and counts it as an override until reset`() {
        scope.advanceUntilIdle()
        vm.state.value.locale shouldBe DeviceLocaleState("en-US", "en-US")
        vm.overrideContributor.overridesFor(emulator) shouldBe emptyList()

        vm.handle(LocaleIntent.Apply("ar-XB"))
        scope.advanceUntilIdle()

        vm.state.value.locale shouldBe DeviceLocaleState("ar-XB", "en-US")
        events.last() shouldBe LocaleEvent.Applied("ar-XB")
        vm.overrideContributor.overridesFor(emulator) shouldBe listOf(OverrideSummary("locale", "Language ar-XB"))
        vm.overrideResetUseCase.currentValue(emulator) shouldBe "ar-XB"

        vm.handle(LocaleIntent.Reset)
        scope.advanceUntilIdle()
        vm.state.value.locale shouldBe DeviceLocaleState("en-US", "en-US")
        events.last() shouldBe LocaleEvent.ResetTo("en-US")
    }

    @Test
    fun `reset all goes through the override reset use case`() = runTest {
        scope.advanceUntilIdle()
        vm.handle(LocaleIntent.Apply("pl-PL"))
        scope.advanceUntilIdle()

        vm.overrideResetUseCase.reset(emulator) shouldBe OverrideResetOutcome.Success
        locale shouldBe "en-US"
        vm.overrideResetUseCase.reset(emulator) shouldBe OverrideResetOutcome.NoOverride
    }

    @Test
    fun `search shows test locales by default, matches name and code, and offers a typed tag`() {
        vm.state.value.rows.map { it.tag } shouldBe listOf("en-XA", "ar-XB", "ar-EG", "he-IL")
        vm.handle(LocaleIntent.Search("polski"))
        vm.state.value.rows.map { it.tag } shouldBe listOf("pl-PL")
        vm.handle(LocaleIntent.Search("pt-PT"))
        vm.state.value.rows.single().explanation shouldBe "Use this language tag"
        vm.handle(LocaleIntent.Search("zzzz zz"))
        vm.state.value.rows shouldBe emptyList()
    }

    @Test
    fun `an invalid tag is reported, not sent`() {
        scope.advanceUntilIdle()
        vm.handle(LocaleIntent.Apply("not a tag"))
        scope.advanceUntilIdle()
        (events.last() as LocaleEvent.Failed).message shouldBe "“not a tag” is not a language tag, e.g. pt-BR"
        locale shouldBe "en-US"
    }
}

class LocationViewModelTest {
    private val scope = TestScope()
    private val results = mutableListOf<Pair<String, Boolean>>()
    private val selected = MutableStateFlow<SelectedDeviceState>(online(emulator))
    private val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "OK\n", "") })
    private val vm = LocationViewModel(scope, TestDispatchers(StandardTestDispatcher(scope.testScheduler)), EmulatorLocationUseCase(transport), selected) { m, ok -> results += m to ok }

    @Test
    fun `a preset sets the fix and is shown as current`() {
        scope.advanceUntilIdle()
        vm.state.value.emulator shouldBe true

        vm.handle(LocationIntent.Preset("Warsaw"))
        scope.advanceUntilIdle()

        vm.state.value.current shouldBe ("Warsaw" to GeoPoint.of(52.2297, 21.0122))
        results.single() shouldBe ("Location set to Warsaw (52.2297, 21.0122)" to true)
    }

    @Test
    fun `custom coordinates are validated per field`() {
        scope.advanceUntilIdle()
        vm.handle(LocationIntent.Custom("91", "-200"))
        vm.state.value.latitudeError shouldBe "Latitude must be −90 to 90"
        vm.state.value.longitudeError shouldBe "Longitude must be −180 to 180"

        vm.handle(LocationIntent.Custom("37.422", "-122.0841"))
        scope.advanceUntilIdle()
        results.single().first shouldBe "Location set to 37.4220, -122.0841"
        vm.state.value.latitudeError shouldBe null
    }

    @Test
    fun `a physical device is not an emulator`() {
        selected.value = online(phone)
        scope.advanceUntilIdle()
        vm.state.value.emulator shouldBe false
    }

    @Test
    fun `coordinates are formatted with four decimals`() {
        LocationViewModel.format(21.0) shouldBe "21.0000"
        LocationViewModel.format(-0.12761) shouldBe "-0.1276"
        LocationViewModel.format(-0.00001) shouldBe "0.0000"
    }
}
