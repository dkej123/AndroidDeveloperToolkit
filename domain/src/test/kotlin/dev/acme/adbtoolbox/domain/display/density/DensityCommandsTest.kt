package dev.acme.adbtoolbox.domain.display.density

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DensityCommandsTest {

    @Test
    fun `read builds the exact wm density read command`() {
        (DensityCommands.read() as AdbOperation.Shell).command.render() shouldBe "wm density"
    }

    @Test
    fun `apply builds the exact wm density set command for a validated dpi`() {
        (DensityCommands.apply(560) as AdbOperation.Shell).command.render() shouldBe "wm density 560"
    }

    @Test
    fun `reset builds the exact wm density reset command`() {
        (DensityCommands.reset() as AdbOperation.Shell).command.render() shouldBe "wm density reset"
    }

    @Test
    fun `apply rejects a nonpositive dpi at construction`() {
        shouldThrow<IllegalArgumentException> { DensityCommands.apply(0) }
        shouldThrow<IllegalArgumentException> { DensityCommands.apply(-5) }
    }
}
