package io.github.dkej123.devicecockpit.domain.recording

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.discovery.ToolVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ScrcpyRecordingTest {

    @Test
    fun `arguments record to the given file without a window or playback`() {
        ScrcpyRecording.arguments(DeviceSerial.of("emulator-5554"), "/tmp/.capture-1.tmp") shouldBe listOf(
            "-s", "emulator-5554",
            "--no-window", "--no-playback",
            "--record=/tmp/.capture-1.tmp", "--record-format=mp4",
        )
    }

    @Test
    fun `scrcpy 2_4 and newer can record without a window`() {
        listOf("2.4", "2.7", "3.0", "4.1", "10.0.1").forEach {
            ScrcpyRecording.supports(ToolVersion.of(it)) shouldBe true
        }
    }

    @Test
    fun `older or unparseable scrcpy versions fall back to screenrecord`() {
        listOf("1.25", "2.0", "2.3.1", "dev", "v").forEach {
            ScrcpyRecording.supports(ToolVersion.of(it)) shouldBe false
        }
    }
}
