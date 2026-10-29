package io.github.dkej123.devicecockpit.application.locale

import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.locale.DeviceLocalePort
import io.github.dkej123.devicecockpit.domain.locale.LocaleAction
import io.github.dkej123.devicecockpit.domain.locale.LocaleRead
import io.github.dkej123.devicecockpit.domain.locale.LocaleTag
import io.github.dkej123.devicecockpit.domain.location.GeoPoint
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("emulator-5554")

private class FakeLocales(var locale: String = "en-US", var failure: String? = null) : DeviceLocalePort {
    var locales: List<String> = listOf(locale)
    val actions = mutableListOf<LocaleAction>()

    override suspend fun run(serial: DeviceSerial, action: LocaleAction): LocaleRead {
        actions += action
        failure?.let { return LocaleRead.Failed(it) }
        if (action is LocaleAction.Set) {
            locales = action.locales.map { it.value }
            locale = locales.first()
        }
        return LocaleRead.Locales(locales)
    }
}

class DeviceLocaleUseCaseTest {

    @Test
    fun `reset preserves languages added since the original snapshot`() = runTest {
        val port = FakeLocales("pl-PL").apply { locales = listOf("pl-PL", "en-US", "de-DE") }
        val useCase = DeviceLocaleUseCase(port, InMemoryOriginalLocaleStore())
        useCase.read(serial)
        port.locales = listOf("de-DE", "pl-PL", "en-US", "uk-UA")
        useCase.reset(serial)
        port.locales shouldBe listOf("pl-PL", "en-US", "de-DE", "uk-UA")
    }

    @Test
    fun `an unreadable or unsupported current locale list is never overwritten`() = runTest {
        val port = FakeLocales()
        val useCase = DeviceLocaleUseCase(port, InMemoryOriginalLocaleStore())
        useCase.read(serial)
        port.failure = "Device disconnected"
        useCase.set(serial, LocaleTag.of("pl-PL")!!) shouldBe LocaleResult.Failed("Device disconnected")
        useCase.reset(serial) shouldBe LocaleResult.Failed("Device disconnected")
        port.failure = null
        port.locales = listOf("en-US", "ca-ES-valencia")
        useCase.set(serial, LocaleTag.of("pl-PL")!!) shouldBe LocaleResult.Failed("Cannot preserve device language: ca-ES-valencia")
        port.actions.filterIsInstance<LocaleAction.Set>() shouldBe emptyList()
    }

    @Test
    fun `selecting a language preserves all three languages and reset restores their order after restart`() = runTest {
        val port = FakeLocales("pl-PL").apply { locales = listOf("pl-PL", "en-US", "de-DE") }
        val store = InMemoryOriginalLocaleStore()
        val useCase = DeviceLocaleUseCase(port, store)

        useCase.set(serial, LocaleTag.of("en-US")!!)
        port.locales shouldBe listOf("en-US", "pl-PL", "de-DE")
        useCase.set(serial, LocaleTag.of("ar-XB")!!)
        port.locales shouldBe listOf("ar-XB", "en-US", "pl-PL", "de-DE")

        DeviceLocaleUseCase(port, store).reset(serial)
        port.locales shouldBe listOf("pl-PL", "en-US", "de-DE", "ar-XB")
    }

    @Test
    fun `a legacy stored original and languages added outside the tool are preserved`() = runTest {
        val port = FakeLocales("en-US").apply { locales = listOf("en-US", "pl-PL", "de-DE") }
        val store = InMemoryOriginalLocaleStore().apply { put(serial, "pl-PL") }
        val useCase = DeviceLocaleUseCase(port, store)
        useCase.set(serial, LocaleTag.of("fr-FR")!!)
        port.locales = port.locales + "uk-UA"
        useCase.set(serial, LocaleTag.of("de-DE")!!)
        port.locales shouldBe listOf("de-DE", "fr-FR", "en-US", "pl-PL", "uk-UA")
        useCase.reset(serial)
        port.locales shouldBe listOf("pl-PL", "de-DE", "fr-FR", "en-US", "uk-UA")
    }

    @Test
    fun `the first locale seen is the original, and a change is an override`() = runTest {
        val port = FakeLocales()
        val useCase = DeviceLocaleUseCase(port, InMemoryOriginalLocaleStore())

        useCase.set(serial, LocaleTag.of("ar-XB")!!) shouldBe LocaleResult.Applied(DeviceLocaleState("ar-XB", "en-US"))
        (useCase.read(serial) as LocaleResult.Applied).state.overridden shouldBe true
    }

    @Test
    fun `reset returns to the original`() = runTest {
        val port = FakeLocales()
        val useCase = DeviceLocaleUseCase(port, InMemoryOriginalLocaleStore())
        useCase.set(serial, LocaleTag.of("pl-PL")!!)

        val reset = useCase.reset(serial) as LocaleResult.Applied

        reset.state shouldBe DeviceLocaleState("en-US", "en-US")
        reset.state.overridden shouldBe false
        port.locale shouldBe "en-US"
    }

    @Test
    fun `a stored original survives across use case instances`() = runTest {
        val store = InMemoryOriginalLocaleStore()
        store.put(serial, "de-DE")
        val port = FakeLocales(locale = "pl-PL")

        (DeviceLocaleUseCase(port, store).read(serial) as LocaleResult.Applied).state shouldBe DeviceLocaleState("pl-PL", "de-DE")
    }

    @Test
    fun `helper failures are reported and change nothing`() = runTest {
        val store = InMemoryOriginalLocaleStore()
        val useCase = DeviceLocaleUseCase(FakeLocales(failure = "Device helper did not start"), store)

        useCase.set(serial, LocaleTag.of("pl-PL")!!) shouldBe LocaleResult.Failed("Device helper did not start")
        store.get(serial) shouldBe null
    }
}

class EmulatorLocationUseCaseTest {

    private val warsaw = GeoPoint.of(52.2297, 21.0122)!!

    @Test
    fun `sends the fix to an emulator and reports the console's answer`() = runTest {
        val ok = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "OK\n", "") })
        EmulatorLocationUseCase(ok).set(serial, warsaw) shouldBe LocationResult.Set(warsaw)

        val ko = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(1), "", "error: could not connect to TCP port 5554") })
        EmulatorLocationUseCase(ko).set(serial, warsaw) shouldBe LocationResult.Failed("error: could not connect to TCP port 5554")
    }

    @Test
    fun `physical devices and network-connected emulators are refused without a command`() = runTest {
        val transport = FakeAdbTransport()

        EmulatorLocationUseCase(transport).set(DeviceSerial.of("192.168.1.20:5555"), warsaw) shouldBe LocationResult.NotAnEmulator
        transport.textRequests shouldBe emptyList()
    }
}
