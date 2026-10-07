package dev.acme.adbtoolbox.adapters.jvm.mcp

import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Test

class AwtImageScalerTest {
    @Test
    fun `scales a PNG to the requested size and refuses garbage`() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1080, 2400, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

        val scaled = ImageIO.read(ByteArrayInputStream(AwtImageScaler().scale(png, 393, 873)!!))

        scaled.width shouldBe 393
        scaled.height shouldBe 873
        AwtImageScaler().scale(byteArrayOf(1, 2, 3), 10, 10) shouldBe null
    }
}
