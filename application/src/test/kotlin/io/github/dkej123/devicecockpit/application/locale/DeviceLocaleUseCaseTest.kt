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
    val actions = mutableListOf<LocaleAction>()

    override suspend fun run(serial: DeviceSerial, action: LocaleAction): LocaleRead {
        actions += action
        failure?.let { return LocaleRead.Failed(it) }
        if (action is LocaleAction.Set) locale = action.locales.first().value
        return LocaleRead.Locales(listOf(locale))
    }
}

class DeviceLocaleUseCaseTest {

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
