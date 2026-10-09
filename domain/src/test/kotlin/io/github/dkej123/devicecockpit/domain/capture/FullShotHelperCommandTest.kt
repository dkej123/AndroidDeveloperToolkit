package io.github.dkej123.devicecockpit.domain.capture

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class FullShotHelperCommandTest {

    private val serial = DeviceSerial.of("emulator-5554")
    private val remotePath = "/data/local/tmp/adb-toolbox-app-info-0123abcd.jar"

    @Test
    fun `runs the full-shot entry point of the pushed helper with app_process, bounded`() {
        val request = FullShotHelperCommand.request(serial, remotePath)

        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "CLASSPATH=$remotePath app_process / io.github.dkej123.devicecockpit.devicehelper.FullShotMain " +
            "'${FullShotHelperCommand.OUTPUT_PATH}'"
        request.timeout shouldBe FullShotHelperCommand.TIMEOUT
    }

    @Test
    fun `the rendered file is read with exec-out cat and removed with rm -f`() {
        val read = FullShotHelperCommand.readRequest(serial, "/data/local/tmp/adbtoolbox-fullshot.png")
        val remove = FullShotHelperCommand.removeRequest(serial, "/data/local/tmp/adbtoolbox-fullshot.png")

        read.operation shouldBe AdbOperation.Exec(listOf("cat", "/data/local/tmp/adbtoolbox-fullshot.png"))
        (remove.operation as AdbOperation.Shell).command.render() shouldBe "rm -f '/data/local/tmp/adbtoolbox-fullshot.png'"
    }

    @Test
    fun `parses the rendered file, its size and whether the content was cut`() {
        val result = ok(
            "ADBTOOLBOX-FULLSHOT 1\nfile=/data/local/tmp/adbtoolbox-fullshot.png\nsize=1080x4211\ntruncated=0\nEND\n",
        )

        FullShotHelperCommand.parse(result) shouldBe FullShotRender.Rendered(
            remotePath = "/data/local/tmp/adbtoolbox-fullshot.png",
            widthPx = 1080,
            heightPx = 4211,
            truncated = false,
        )
        FullShotHelperCommand.parse(ok(result.stdout.replace("truncated=0", "truncated=1")))
            .shouldBeInstanceOf<FullShotRender.Rendered>().truncated shouldBe true
    }

    @Test
    fun `a helper error is reported with its reason`() {
        val result = ok("ADBTOOLBOX-FULLSHOT 1\nerror=java.lang.IllegalStateException: no app is in the foreground\nEND\n")

        FullShotHelperCommand.parse(result) shouldBe
            FullShotRender.Failed("Full screenshot failed: no app is in the foreground")
    }

    @Test
    fun `a missing header, file or size, or a transport failure, is a failure`() {
        FullShotHelperCommand.parse(ok("Error: Could not find or load main class\n"))
            .shouldBeInstanceOf<FullShotRender.Failed>()
        FullShotHelperCommand.parse(ok("ADBTOOLBOX-FULLSHOT 1\nsize=1080x4211\nEND\n"))
            .shouldBeInstanceOf<FullShotRender.Failed>()
        FullShotHelperCommand.parse(ok("ADBTOOLBOX-FULLSHOT 1\nfile=/data/local/tmp/x.png\nsize=wide\nEND\n"))
            .shouldBeInstanceOf<FullShotRender.Failed>()
        FullShotHelperCommand.parse(AdbTextResult(AdbOutcome.TimedOut, "", "")) shouldBe
            FullShotRender.Failed("Full screenshot timed out")
    }

    @Test
    fun `a helper that never started is distinguishable, so the caller can redeploy it`() {
        FullShotHelperCommand.parse(ok("Error: Could not find or load main class\n")) shouldBe
            FullShotRender.Failed("Full screenshot helper did not start", helperStarted = false)
    }

    private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(exitCode = 0), stdout = stdout, stderr = "")
}
