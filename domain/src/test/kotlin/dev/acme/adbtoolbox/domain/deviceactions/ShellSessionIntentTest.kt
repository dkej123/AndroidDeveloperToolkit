package dev.acme.adbtoolbox.domain.deviceactions

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [ShellSessionIntent] carries only the exact `adb -s $serial shell` argv ADR 0007's Open-shell
 * platform adapter must pre-seed into a new terminal tab — a platform-neutral value (no IntelliJ
 * type), so it can be constructed and asserted on in a plain `:domain` test.
 */
class ShellSessionIntentTest {

    @Test
    fun `commandLine renders the exact serial-scoped adb shell invocation`() {
        val intent = ShellSessionIntent(
            serial = DeviceSerial.of("R58N90ABCDE"),
            adbExecutablePath = "/opt/homebrew/bin/adb",
        )

        intent.commandLine() shouldBe "/opt/homebrew/bin/adb -s R58N90ABCDE shell"
    }
}
