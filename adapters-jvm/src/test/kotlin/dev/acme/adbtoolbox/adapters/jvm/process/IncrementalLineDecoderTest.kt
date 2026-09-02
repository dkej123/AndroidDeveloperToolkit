package dev.acme.adbtoolbox.adapters.jvm.process

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class IncrementalLineDecoderTest {

    @Test
    fun `emits nothing until a newline is seen`() {
        val decoder = IncrementalLineDecoder()

        val lines = decoder.decode("no newline yet".encodeToByteArray())

        lines shouldBe emptyList()
    }

    @Test
    fun `emits a completed line and keeps the remainder pending`() {
        val decoder = IncrementalLineDecoder()

        val lines = decoder.decode("first\nsecond-partial".encodeToByteArray())

        lines shouldBe listOf("first")
    }

    @Test
    fun `strips a trailing carriage return before the newline`() {
        val decoder = IncrementalLineDecoder()

        val lines = decoder.decode("windows-style\r\n".encodeToByteArray())

        lines shouldBe listOf("windows-style")
    }

    @Test
    fun `reassembles a multibyte UTF-8 character split exactly at the encoding boundary`() {
        val decoder = IncrementalLineDecoder()
        val fullLine = "prefix-é-😀-suffix\n" // e-acute + emoji, both multi-byte
        val bytes = fullLine.encodeToByteArray()

        // Split at every possible byte boundary and confirm the reassembled text is always
        // correct, including boundaries that fall inside a multi-byte sequence.
        for (splitAt in 1 until bytes.size) {
            val fresh = IncrementalLineDecoder()
            val firstChunk = bytes.copyOfRange(0, splitAt)
            val secondChunk = bytes.copyOfRange(splitAt, bytes.size)

            val firstLines = fresh.decode(firstChunk)
            val secondLines = fresh.decode(secondChunk)

            (firstLines + secondLines) shouldBe listOf("prefix-é-😀-suffix")
        }
    }

    @Test
    fun `finish flushes a final line with no trailing newline`() {
        val decoder = IncrementalLineDecoder()
        decoder.decode("no newline at eof".encodeToByteArray())

        val lines = decoder.finish()

        lines shouldBe listOf("no newline at eof")
    }

    @Test
    fun `finish returns nothing when there is no pending partial line`() {
        val decoder = IncrementalLineDecoder()
        decoder.decode("complete\n".encodeToByteArray())

        val lines = decoder.finish()

        lines shouldBe emptyList()
    }

    @Test
    fun `handles multiple lines within a single chunk`() {
        val decoder = IncrementalLineDecoder()

        val lines = decoder.decode("a\nb\nc\nd-partial".encodeToByteArray())

        lines shouldBe listOf("a", "b", "c")
    }
}
