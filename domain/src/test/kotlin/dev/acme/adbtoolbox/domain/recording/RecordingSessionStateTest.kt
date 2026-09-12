package dev.acme.adbtoolbox.domain.recording

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("R58N90ABCDE")

class RecordingSessionStateTest {

    @Test
    fun `every state variant retains its originating serial`() {
        val states: List<RecordingSessionState> = listOf(
            RecordingSessionState.Idle(serial),
            RecordingSessionState.Starting(serial),
            RecordingSessionState.Recording(serial, startedAt = 3.seconds),
            RecordingSessionState.Stopping(serial),
            RecordingSessionState.Pulling(serial),
            RecordingSessionState.Saved(serial, CaptureLocation("/fake/screen-1.mp4")),
            RecordingSessionState.Error(serial, RecordingSessionError.StartFailure("boom")),
        )

        states.forEach { it.serial shouldBe serial }
    }

    @Test
    fun `Saved carries the committed local location a Reveal action gates on`() {
        val location = CaptureLocation("/fake/screen-1.mp4")
        val state = RecordingSessionState.Saved(serial, location)

        state.location shouldBe location
    }

    @Test
    fun `a pull failure that leaves the remote file in place is distinct from one that does not`() {
        val recoverable = RecordingSessionError.PullFailure("timed out", remoteFileRemaining = true)
        val unrecoverable = RecordingSessionError.PullFailure("device disconnected", remoteFileRemaining = false)

        recoverable.remoteFileRemaining shouldBe true
        unrecoverable.remoteFileRemaining shouldBe false
    }
}
