package io.github.dkej123.devicecockpit.adapters.jvm.mcp

import io.github.dkej123.devicecockpit.domain.capture.MarkBox
import io.github.dkej123.devicecockpit.domain.capture.ScreenMark
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Test

private fun png(draw: (java.awt.Graphics2D) -> Unit): ByteArray {
    val image = BufferedImage(400, 800, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        color = Color.WHITE
        fillRect(0, 0, 400, 800)
        draw(this)
        dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

class AwtScreenMarkerTest {
    private val marker = AwtScreenMarker()

    @Test
    fun `two separate shapes on a plain background are two boxes, top first`() {
        val screen = png {
            it.color = Color(0x6750A4)
            it.fillRoundRect(40, 100, 120, 48, 16, 16)
            it.color = Color(0x1E88E5)
            it.fillOval(250, 500, 64, 64)
        }

        val boxes = marker.detect(screen)

        boxes shouldHaveSize 2
        boxes[0].left shouldBeInRange 36..42
        boxes[0].top shouldBeInRange 96..102
        boxes[0].width shouldBeInRange 116..128
        boxes[1].centerX.toInt() shouldBeInRange 278..286
        boxes[1].centerY.toInt() shouldBeInRange 528..536
    }

    @Test
    fun `a blank screen has no shapes and garbage is no image`() {
        marker.detect(png { }) shouldBe emptyList()
        marker.detect(byteArrayOf(1, 2, 3)) shouldBe emptyList()
        marker.draw(byteArrayOf(1, 2, 3), emptyList()) shouldBe null
    }

    @Test
    fun `drawing keeps the size and paints the box`() {
        val drawn = marker.draw(png { }, listOf(ScreenMark("m1", MarkBox(100, 100, 80, 40), fromTree = false), ScreenMark("12", MarkBox(10, 300, 50, 50), fromTree = true)))!!
        val image = ImageIO.read(ByteArrayInputStream(drawn))

        image.width shouldBe 400
        image.height shouldBe 800
        image.getRGB(140, 140) and 0xFFFFFF shouldNotBe 0xFFFFFF // bottom edge of the m1 box
        image.getRGB(300, 700) and 0xFFFFFF shouldBe 0xFFFFFF
    }
}
