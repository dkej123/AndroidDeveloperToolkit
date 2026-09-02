package dev.acme.adbtoolbox.adapters.jvm.process

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes a process output stream to text incrementally, one chunk at a time, without assuming a
 * chunk boundary lands on a UTF-8 character boundary or a line boundary. Malformed/unmappable
 * input is replaced rather than throwing, since process output is not a trusted, well-formed
 * stream. Not thread-safe: one instance per stream.
 */
internal class IncrementalLineDecoder {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val pendingLine = StringBuilder()

    // CharsetDecoder does not retain bytes left unconsumed at the end of a decode() call (e.g. a
    // multi-byte character split across chunk boundaries) — the caller must carry them forward and
    // prepend them to the next chunk.
    private var pendingBytes = ByteArray(0)

    /** Decodes [bytes], returning any newly completed lines. Partial data is retained internally. */
    fun decode(bytes: ByteArray): List<String> {
        val combined = if (pendingBytes.isEmpty()) bytes else pendingBytes + bytes
        appendDecoded(ByteBuffer.wrap(combined), endOfInput = false)
        return extractCompletedLines()
    }

    /**
     * Signals end of stream: flushes any buffered partial multi-byte sequence and returns the
     * final partial line (if any) as a completed line, since there will be no further newline.
     */
    fun finish(): List<String> {
        appendDecoded(ByteBuffer.wrap(pendingBytes), endOfInput = true)
        pendingBytes = ByteArray(0)
        val lines = extractCompletedLines().toMutableList()
        if (pendingLine.isNotEmpty()) {
            lines += pendingLine.toString()
            pendingLine.clear()
        }
        return lines
    }

    private fun appendDecoded(input: ByteBuffer, endOfInput: Boolean) {
        val out = CharBuffer.allocate((input.remaining() + 16) * 2)
        decoder.decode(input, out, endOfInput)
        if (endOfInput) {
            decoder.flush(out)
        }
        out.flip()
        pendingLine.append(out)

        pendingBytes = if (input.hasRemaining()) {
            ByteArray(input.remaining()).also { input.get(it) }
        } else {
            ByteArray(0)
        }
    }

    private fun extractCompletedLines(): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        while (true) {
            val newlineIndex = pendingLine.indexOf("\n", start)
            if (newlineIndex < 0) break
            var end = newlineIndex
            if (end > start && pendingLine[end - 1] == '\r') end--
            lines += pendingLine.substring(start, end)
            start = newlineIndex + 1
        }
        if (start > 0) pendingLine.delete(0, start)
        return lines
    }
}
