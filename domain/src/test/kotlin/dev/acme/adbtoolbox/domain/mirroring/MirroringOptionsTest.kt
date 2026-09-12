package dev.acme.adbtoolbox.domain.mirroring

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("R58N90ABCDE")

class MirroringOptionsTest {

    @Test
    fun `default options produce only the explicit serial flag`() {
        buildScrcpyArguments(serial, MirroringOptions()) shouldBe listOf("-s", "R58N90ABCDE")
    }

    @Test
    fun `stay-awake option appends --stay-awake`() {
        buildScrcpyArguments(serial, MirroringOptions(stayAwake = true)) shouldBe
            listOf("-s", "R58N90ABCDE", "--stay-awake")
    }

    @Test
    fun `show-touches option appends --show-touches`() {
        buildScrcpyArguments(serial, MirroringOptions(showTouches = true)) shouldBe
            listOf("-s", "R58N90ABCDE", "--show-touches")
    }

    @Test
    fun `max size option appends --max-size with the pixel value`() {
        buildScrcpyArguments(serial, MirroringOptions(maxSize = 1920)) shouldBe
            listOf("-s", "R58N90ABCDE", "--max-size", "1920")
    }

    @Test
    fun `video bit rate option appends --video-bit-rate in scrcpy megabit shorthand`() {
        buildScrcpyArguments(serial, MirroringOptions(videoBitRateMbps = 8)) shouldBe
            listOf("-s", "R58N90ABCDE", "--video-bit-rate", "8M")
    }

    @Test
    fun `every option combines into one structured argument vector, never a shell string`() {
        val options = MirroringOptions(
            stayAwake = true,
            showTouches = true,
            maxSize = 1920,
            videoBitRateMbps = 8,
        )

        buildScrcpyArguments(serial, options) shouldBe listOf(
            "-s", "R58N90ABCDE",
            "--stay-awake",
            "--show-touches",
            "--max-size", "1920",
            "--video-bit-rate", "8M",
        )
    }

    @Test
    fun `a different serial is reflected verbatim in the -s argument, never assumed`() {
        val other = DeviceSerial.of("192.168.1.42:5555")

        buildScrcpyArguments(other, MirroringOptions()) shouldBe listOf("-s", "192.168.1.42:5555")
    }

    @Test
    fun `max size rejects a non-positive value`() {
        runCatching { MirroringOptions(maxSize = 0) }.isFailure shouldBe true
        runCatching { MirroringOptions(maxSize = -10) }.isFailure shouldBe true
    }

    @Test
    fun `video bit rate rejects a non-positive value`() {
        runCatching { MirroringOptions(videoBitRateMbps = 0) }.isFailure shouldBe true
        runCatching { MirroringOptions(videoBitRateMbps = -1) }.isFailure shouldBe true
    }
}
