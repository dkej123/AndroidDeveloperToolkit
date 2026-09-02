package dev.acme.adbtoolbox.adapters.adb.ddmlib

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class Utf8LineDecoderTest {

    @Test
    fun `splits a single chunk containing multiple lines`() {
        val decoder = Utf8LineDecoder()
        val bytes = "one\ntwo\nthree".toByteArray(Charsets.UTF_8)

        val lines = decoder.decode(bytes, 0, bytes.size)

        lines shouldBe listOf("one", "two")
    }

    @Test
    fun `strips a trailing carriage return before the newline`() {
        val decoder = Utf8LineDecoder()
        val bytes = "windows-line\r\nnext".toByteArray(Charsets.UTF_8)

        val lines = decoder.decode(bytes, 0, bytes.size)

        lines shouldBe listOf("windows-line")
    }

    @Test
    fun `a line split across chunks is only emitted once complete`() {
        val decoder = Utf8LineDecoder()
        val first = "partial-".toByteArray(Charsets.UTF_8)
        val second = "line\n".toByteArray(Charsets.UTF_8)

        val fromFirst = decoder.decode(first, 0, first.size)
        val fromSecond = decoder.decode(second, 0, second.size)

        fromFirst shouldBe emptyList()
        fromSecond shouldBe listOf("partial-line")
    }

    @Test
    fun `finish emits a trailing partial line with no newline`() {
        val decoder = Utf8LineDecoder()
        val bytes = "no newline yet".toByteArray(Charsets.UTF_8)
        decoder.decode(bytes, 0, bytes.size)

        val lines = decoder.finish()

        lines shouldBe listOf("no newline yet")
    }

    @Test
    fun `finish on a stream that already ended cleanly with a newline emits nothing extra`() {
        val decoder = Utf8LineDecoder()
        val bytes = "clean\n".toByteArray(Charsets.UTF_8)
        decoder.decode(bytes, 0, bytes.size)

        decoder.finish() shouldBe emptyList()
    }
}
