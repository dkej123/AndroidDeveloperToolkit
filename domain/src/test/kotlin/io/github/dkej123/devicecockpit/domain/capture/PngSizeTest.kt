package io.github.dkej123.devicecockpit.domain.capture

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PngSizeTest {
    @Test
    fun `reads width and height from the IHDR chunk and refuses anything else`() {
        val header = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 4, 56, 0, 0, 9, 96)

        PngSize.of(header) shouldBe (1080 to 2400)
        PngSize.of(byteArrayOf(1, 2, 3)) shouldBe null
        PngSize.of(header.copyOf(20)) shouldBe null
    }
}
