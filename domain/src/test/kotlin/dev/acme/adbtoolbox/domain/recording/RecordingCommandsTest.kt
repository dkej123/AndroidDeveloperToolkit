package dev.acme.adbtoolbox.domain.recording

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RecordingCommandsTest {

    @Test
    fun `remotePath places the base file name under sdcard`() {
        RecordingCommands.remotePath("screen-20260901-120000.mp4") shouldBe
            "/sdcard/screen-20260901-120000.mp4"
    }

    @Test
    fun `startRecording renders design IMPLEMENTATION section 4's screenrecord command`() {
        RecordingCommands.startRecording("/sdcard/screen-1.mp4").render() shouldBe
            "screenrecord '/sdcard/screen-1.mp4'"
    }

    @Test
    fun `startRecording quotes a path containing a single quote rather than breaking out of it`() {
        RecordingCommands.startRecording("/sdcard/it's-a-test.mp4").render() shouldBe
            "screenrecord '/sdcard/it'\\''s-a-test.mp4'"
    }

    @Test
    fun `pull is a literal cat argv over exec-out, never shell-parsed`() {
        RecordingCommands.pull("/sdcard/screen-1.mp4") shouldBe listOf("cat", "/sdcard/screen-1.mp4")
    }

    @Test
    fun `cleanup renders design IMPLEMENTATION section 4's remote rm command`() {
        RecordingCommands.cleanup("/sdcard/screen-1.mp4").render() shouldBe
            "rm -f '/sdcard/screen-1.mp4'"
    }
}
