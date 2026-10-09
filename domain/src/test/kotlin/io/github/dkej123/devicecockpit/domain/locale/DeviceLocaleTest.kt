package io.github.dkej123.devicecockpit.domain.locale

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LocaleTagTest {

    @Test
    fun `normalizes case and underscores`() {
        LocaleTag.of("pl_pl")?.value shouldBe "pl-PL"
        LocaleTag.of(" EN-xa ")?.value shouldBe "en-XA"
        LocaleTag.of("zh-hant-tw")?.value shouldBe "zh-Hant-TW"
        LocaleTag.of("es-419")?.value shouldBe "es-419"
        LocaleTag.of("fil")?.value shouldBe "fil"
    }

    @Test
    fun `rejects anything that could not be a language tag`() {
        listOf("", "p", "polish", "pl-PL; reboot", "pl PL", "pl-", "12-PL").forEach { LocaleTag.of(it) shouldBe null }
    }

    @Test
    fun `the catalog has the RTL languages and both pseudo-locales`() {
        LocaleCatalog.entries.filter { it.pseudo }.map { it.tag.value } shouldBe listOf("en-XA", "ar-XB")
        LocaleCatalog.entries.filter { it.rightToLeft }.map { it.tag.value } shouldContain "he-IL"
    }
}

class LocaleHelperCommandTest {

    private val serial = DeviceSerial.of("emulator-5554")
    private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

    @Test
    fun `runs the locale entry point of the pushed helper`() {
        val set = LocaleHelperCommand.request(serial, "/data/local/tmp/h.jar", LocaleAction.Set(listOfNotNull(LocaleTag.of("ar-XB"), LocaleTag.of("en-US"))))
        (set.operation as AdbOperation.Shell).command.render() shouldBe
            "CLASSPATH=/data/local/tmp/h.jar app_process / io.github.dkej123.devicecockpit.devicehelper.LocaleMain set ar-XB,en-US"
        (LocaleHelperCommand.request(serial, "/h.jar", LocaleAction.Read).operation as AdbOperation.Shell).command.render() shouldBe
            "CLASSPATH=/h.jar app_process / io.github.dkej123.devicecockpit.devicehelper.LocaleMain get"
    }

    @Test
    fun `parses the locales, errors and a helper that never started`() {
        LocaleHelperCommand.parse(ok("ADBTOOLBOX-LOCALE 1\nlocales=pl-PL,en-US\nEND\n")) shouldBe LocaleRead.Locales(listOf("pl-PL", "en-US"))
        LocaleHelperCommand.parse(ok("ADBTOOLBOX-LOCALE 1\nerror=java.lang.SecurityException: denied\nEND")) shouldBe
            LocaleRead.Failed("java.lang.SecurityException: denied")
        LocaleHelperCommand.parse(ok("Error: Could not find class")) shouldBe LocaleRead.Failed("Device helper did not start")
        LocaleHelperCommand.parse(AdbTextResult(AdbOutcome.TimedOut, "", "")) shouldBe LocaleRead.Failed("Could not reach the device")
    }
}
