package dev.acme.adbtoolbox.domain.layout

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class UiAutomatorDumpCommandTest {

    private val serial = DeviceSerial.of("emulator-5554")

    @Test
    fun `dumps to the shell user's temp folder, prints the file and always removes it`() {
        val request = UiAutomatorDumpCommand.request(serial)

        request.serial shouldBe serial
        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "uiautomator dump /data/local/tmp/adbtoolbox-ui.xml >/dev/null 2>&1 && " +
            "cat /data/local/tmp/adbtoolbox-ui.xml; rm -f /data/local/tmp/adbtoolbox-ui.xml"
    }

    @Test
    fun `a printed hierarchy is parsed`() {
        val result = AdbTextResult(
            AdbOutcome.Completed(0),
            stdout = """<?xml version='1.0' ?><hierarchy rotation="0"><node class="a.B" bounds="[0,0][10,10]"/></hierarchy>""",
            stderr = "",
        )

        UiAutomatorDumpCommand.parse(result).shouldBeInstanceOf<UiDumpRead.Dumped>().root.className shouldBe "a.B"
    }

    @Test
    fun `nothing printed means the dump was refused, typically while the screen animates`() {
        UiAutomatorDumpCommand.parse(AdbTextResult(AdbOutcome.Completed(1), "", "")) shouldBe UiDumpRead.Empty
        UiAutomatorDumpCommand.parse(AdbTextResult(AdbOutcome.Completed(0), "  \n", "")) shouldBe UiDumpRead.Empty
    }

    @Test
    fun `transport failures and garbage are reported as such`() {
        UiAutomatorDumpCommand.parse(AdbTextResult(AdbOutcome.TimedOut, "", "")) shouldBe
            UiDumpRead.TransportFailed(AdbOutcome.TimedOut)
        UiAutomatorDumpCommand.parse(AdbTextResult(AdbOutcome.Completed(0), "<hierarchy><node>", ""))
            .shouldBeInstanceOf<UiDumpRead.Malformed>()
    }
}
