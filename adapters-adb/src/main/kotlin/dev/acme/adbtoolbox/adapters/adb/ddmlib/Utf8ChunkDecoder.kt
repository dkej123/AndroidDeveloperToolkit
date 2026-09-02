package dev.acme.adbtoolbox.adapters.adb.ddmlib

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes ddmlib's raw shell-output byte chunks to UTF-8 text incrementally, one
 * `IShellOutputReceiver.addOutput(data, offset, length)` call at a time, without assuming a chunk
 * boundary lands on a UTF-8 character boundary. Ddmlib may reuse [ByteArray] across calls, so each
 * chunk is copied out immediately rather than retained by reference. Malformed/unmappable input is
 * replaced rather than throwing, since device shell output is not a trusted, well-formed stream.
 * Not thread-safe: one instance per shell call, mirroring `:adapters-jvm`'s
 * `IncrementalLineDecoder` in spirit but shaped for ddmlib's `(data, offset, length)` callback
 * rather than a fresh chunk `ByteArray` per read.
 */
internal class Utf8ChunkDecoder {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    // CharsetDecoder does not retain bytes left unconsumed at the end of a decode() call (e.g. a
    // multi-byte character split across chunk boundaries) — carried forward and prepended to the
    // next chunk.
    private var pendingBytes = ByteArray(0)

    /** Decodes `data[offset, offset + length)`, returning the text decoded so far from this chunk. */
    fun decode(data: ByteArray, offset: Int, length: Int): String {
        val chunk = data.copyOfRange(offset, offset + length)
        val combined = if (pendingBytes.isEmpty()) chunk else pendingBytes + chunk
        return appendDecoded(ByteBuffer.wrap(combined), endOfInput = false)
    }

    /** Signals end of stream: flushes any buffered partial multi-byte sequence. */
    fun finish(): String {
        val text = appendDecoded(ByteBuffer.wrap(pendingBytes), endOfInput = true)
        pendingBytes = ByteArray(0)
        return text
    }

    private fun appendDecoded(input: ByteBuffer, endOfInput: Boolean): String {
        val out = CharBuffer.allocate((input.remaining() + 16) * 2)
        decoder.decode(input, out, endOfInput)
        if (endOfInput) {
            decoder.flush(out)
        }
        out.flip()
        pendingBytes = if (input.hasRemaining()) {
            ByteArray(input.remaining()).also { input.get(it) }
        } else {
            ByteArray(0)
        }
        return out.toString()
    }
}
