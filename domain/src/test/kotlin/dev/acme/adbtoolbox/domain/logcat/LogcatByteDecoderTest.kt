package dev.acme.adbtoolbox.domain.logcat

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LogcatByteDecoderTest {

    @Test
    fun `decodes a single ascii line terminated by LF`() {
        val decoder = LogcatByteDecoder()

        val lines = decoder.decode("hello world\n".toByteArray(Charsets.UTF_8))

        lines shouldBe listOf("hello world")
    }

    @Test
    fun `decodes CRLF terminated lines without keeping the carriage return`() {
        val decoder = LogcatByteDecoder()

        val lines = decoder.decode("one\r\ntwo\r\n".toByteArray(Charsets.UTF_8))

        lines shouldBe listOf("one", "two")
    }

    @Test
    fun `holds a partial line until its newline arrives`() {
        val decoder = LogcatByteDecoder()

        val first = decoder.decode("partial-".toByteArray(Charsets.UTF_8))
        val second = decoder.decode("line\n".toByteArray(Charsets.UTF_8))

        first shouldBe emptyList()
        second shouldBe listOf("partial-line")
    }

    @Test
    fun `finish flushes a final unterminated line at stream end`() {
        val decoder = LogcatByteDecoder()

        decoder.decode("no trailing newline".toByteArray(Charsets.UTF_8))
        val flushed = decoder.finish()

        flushed shouldBe listOf("no trailing newline")
    }

    @Test
    fun `finish returns nothing when the stream ended cleanly on a newline`() {
        val decoder = LogcatByteDecoder()

        decoder.decode("complete line\n".toByteArray(Charsets.UTF_8))
        val flushed = decoder.finish()

        flushed shouldBe emptyList()
    }

    @Test
    fun `a two-byte utf-8 character split across chunks at every offset decodes correctly`() {
        // U+00E9 'é' encodes as 0xC3 0xA9 (2 bytes).
        val line = "café latte\n".toByteArray(Charsets.UTF_8)
        for (splitOffset in 1 until line.size) {
            val decoder = LogcatByteDecoder()
            val first = decoder.decode(line.copyOfRange(0, splitOffset))
            val second = decoder.decode(line.copyOfRange(splitOffset, line.size))
            (first + second) shouldBe listOf("café latte")
        }
    }

    @Test
    fun `a three-byte utf-8 character split across chunks at every offset decodes correctly`() {
        // U+3042 hiragana 'あ' encodes as 0xE3 0x81 0x82 (3 bytes).
        val line = "あいう\n".toByteArray(Charsets.UTF_8)
        for (splitOffset in 1 until line.size) {
            val decoder = LogcatByteDecoder()
            val first = decoder.decode(line.copyOfRange(0, splitOffset))
            val second = decoder.decode(line.copyOfRange(splitOffset, line.size))
            (first + second) shouldBe listOf("あいう")
        }
    }

    @Test
    fun `a four-byte utf-8 emoji split across chunks at every offset decodes correctly`() {
        // U+1F600 grinning face encodes as 0xF0 0x9F 0x98 0x80 (4 bytes).
        val emoji = String(Character.toChars(0x1F600))
        val line = "boom $emoji done\n".toByteArray(Charsets.UTF_8)
        for (splitOffset in 1 until line.size) {
            val decoder = LogcatByteDecoder()
            val first = decoder.decode(line.copyOfRange(0, splitOffset))
            val second = decoder.decode(line.copyOfRange(splitOffset, line.size))
            (first + second) shouldBe listOf("boom $emoji done")
        }
    }

    @Test
    fun `an invalid utf-8 start byte is replaced rather than crashing`() {
        val decoder = LogcatByteDecoder()

        val bytes = byteArrayOf(0x68, 0x69, 0xFF.toByte(), 0x21, '\n'.code.toByte())
        val lines = decoder.decode(bytes)

        lines shouldBe listOf("hi�!")
    }

    @Test
    fun `a truncated multibyte sequence at end of stream is replaced on finish`() {
        val decoder = LogcatByteDecoder()

        // 0xE3 0x81 is the first two bytes of a 3-byte sequence, never completed.
        decoder.decode(byteArrayOf('x'.code.toByte(), 0xE3.toByte(), 0x81.toByte()))
        val flushed = decoder.finish()

        flushed shouldBe listOf("x�")
    }

    @Test
    fun `an unexpected continuation byte with no lead byte is replaced`() {
        val decoder = LogcatByteDecoder()

        val bytes = byteArrayOf('a'.code.toByte(), 0x80.toByte(), 'b'.code.toByte(), '\n'.code.toByte())
        val lines = decoder.decode(bytes)

        lines shouldBe listOf("a�b")
    }

    @Test
    fun `an incomplete line exceeding the bound is force-flushed instead of growing forever`() {
        val decoder = LogcatByteDecoder(maxPendingLineLength = 16)

        val garbage = "x".repeat(40).toByteArray(Charsets.UTF_8)
        val lines = decoder.decode(garbage)

        lines shouldBe listOf("x".repeat(40))
    }

    @Test
    fun `bounded overflow does not silently drop bytes across many chunks with no newline`() {
        val decoder = LogcatByteDecoder(maxPendingLineLength = 8)

        val allFlushed = mutableListOf<String>()
        repeat(5) {
            allFlushed += decoder.decode("abcdefgh".toByteArray(Charsets.UTF_8))
        }
        allFlushed += decoder.finish()

        allFlushed.joinToString("") shouldBe "abcdefgh".repeat(5)
    }

    @Test
    fun `multiple lines in one chunk are all extracted`() {
        val decoder = LogcatByteDecoder()

        val lines = decoder.decode("first\nsecond\nthird\n".toByteArray(Charsets.UTF_8))

        lines shouldBe listOf("first", "second", "third")
    }
}
