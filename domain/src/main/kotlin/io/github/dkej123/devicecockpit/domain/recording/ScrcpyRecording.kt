package io.github.dkej123.devicecockpit.domain.recording

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.discovery.ToolVersion

private val LEADING_VERSION = Regex("""^(\d+)\.(\d+)""")

/**
 * Screen recording through scrcpy instead of `screenrecord`, which Android stops after 180 s.
 * scrcpy records on the host with no length limit and finalizes the MP4 when it receives SIGTERM.
 */
object ScrcpyRecording {

    /** `--no-window` (scrcpy 2.4+) keeps scrcpy from opening a mirror window while it records. */
    fun supports(version: ToolVersion): Boolean {
        val match = LEADING_VERSION.find(version.raw) ?: return false
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        return major > 2 || (major == 2 && minor >= 4)
    }

    /** The format is forced because [outputPath] is a temp file whose extension is not `.mp4`. */
    fun arguments(serial: DeviceSerial, outputPath: String): List<String> = listOf(
        "-s", serial.value,
        "--no-window", "--no-playback",
        "--record=$outputPath", "--record-format=mp4",
    )
}
