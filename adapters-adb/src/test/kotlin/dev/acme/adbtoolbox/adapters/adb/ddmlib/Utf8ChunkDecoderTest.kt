package dev.acme.adbtoolbox.adapters.adb.ddmlib

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class Utf8ChunkDecoderTest {

    @Test
    fun `decodes a single ascii chunk`() {
        val decoder = Utf8ChunkDecoder()
        val bytes = "hello world".toByteArray(Charsets.UTF_8)

        val text = decoder.decode(bytes, 0, bytes.size)

        text shouldBe "hello world"
    }

    @Test
    fun `a multibyte character split across two chunks is not corrupted`() {
        // U+20AC EURO SIGN encodes as 3 UTF-8 bytes (0xE2 0x82 0xAC); split after the first byte.
        val euro = "€".toByteArray(Charsets.UTF_8)
        check(euro.size == 3)
        val decoder = Utf8ChunkDecoder()

        val first = decoder.decode(byteArrayOf(euro[0]), 0, 1)
        val second = decoder.decode(byteArrayOf(euro[1], euro[2]), 0, 2)

        (first + second) shouldBe "€"
    }

    @Test
    fun `decode copies the offset-length range instead of retaining the backing array`() {
        val shared = "abcXYZ".toByteArray(Charsets.UTF_8)
        val decoder = Utf8ChunkDecoder()

        val text = decoder.decode(shared, 0, 3)
        shared[0] = 'Z'.code.toByte() // ddmlib may reuse/overwrite its buffer after the call returns

        text shouldBe "abc"
    }

    @Test
    fun `malformed input is replaced rather than throwing`() {
        val decoder = Utf8ChunkDecoder()
        val malformed = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

        val text = decoder.decode(malformed, 0, malformed.size)

        text shouldBe "��"
    }

    @Test
    fun `finish flushes a pending partial multibyte sequence`() {
        val euro = "€".toByteArray(Charsets.UTF_8)
        val decoder = Utf8ChunkDecoder()
        decoder.decode(byteArrayOf(euro[0], euro[1]), 0, 2)

        val flushed = decoder.finish()

        flushed shouldBe "�"
    }

    @Test
    fun `finish on a clean stream returns empty text`() {
        val decoder = Utf8ChunkDecoder()
        val bytes = "clean".toByteArray(Charsets.UTF_8)
        decoder.decode(bytes, 0, bytes.size)

        decoder.finish() shouldBe ""
    }
}
