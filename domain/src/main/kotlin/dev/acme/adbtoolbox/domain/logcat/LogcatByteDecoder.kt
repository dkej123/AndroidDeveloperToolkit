package dev.acme.adbtoolbox.domain.logcat

private const val REPLACEMENT_CHARACTER = '�'

/**
 * A hand-rolled, chunk-boundary-safe UTF-8 decoder with no `java.nio`/`java.nio.charset`
 * dependency, so it stays usable from a future KMP target. Mirrors the incremental-decode shape of
 * `:adapters-jvm`'s `IncrementalLineDecoder` and `:adapters-adb`'s `Utf8ChunkDecoder`/
 * `Utf8LineDecoder` (carry a partial multi-byte sequence forward across chunk boundaries), but
 * those two rely on `java.nio.charset.CharsetDecoder`, which is JVM-only and therefore off-limits
 * to `:domain` per docs/adr/0002. Invalid byte sequences are replaced with U+FFFD rather than
 * throwing, since a device's logcat stream is not a trusted, well-formed source.
 */
private class Utf8Decoder {

    // Bytes of an in-progress multi-byte sequence that hasn't been fully consumed yet, carried
    // forward and prepended to the next chunk. Never holds more bytes than a single UTF-8
    // sequence (at most 4).
    private var pending = ByteArray(0)

    /** Decodes [bytes], returning the text decoded so far. Any trailing partial sequence is retained. */
    fun decode(bytes: ByteArray): String {
        val combined = if (pending.isEmpty()) bytes else pending + bytes
        return consume(combined, endOfInput = false)
    }

    /** Signals end of stream: any retained partial sequence is now known-incomplete and is replaced. */
    fun finish(): String {
        val text = consume(pending, endOfInput = true)
        pending = ByteArray(0)
        return text
    }

    private fun consume(bytes: ByteArray, endOfInput: Boolean): String {
        val out = StringBuilder(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val sequenceLength = when {
                b0 < 0x80 -> 1
                b0 and 0xE0 == 0xC0 -> 2
                b0 and 0xF0 == 0xE0 -> 3
                b0 and 0xF8 == 0xF0 -> 4
                else -> 0 // invalid lead byte (stray continuation byte or 0xF8..0xFF)
            }

            if (sequenceLength == 0) {
                out.append(REPLACEMENT_CHARACTER)
                i++
                continue
            }

            if (sequenceLength == 1) {
                out.append(b0.toChar())
                i++
                continue
            }

            if (i + sequenceLength > bytes.size) {
                if (!endOfInput) {
                    // Incomplete trailing sequence: carry it forward to the next chunk.
                    pending = bytes.copyOfRange(i, bytes.size)
                    return out.toString()
                }
                // Stream ended mid-sequence: it can never be completed.
                out.append(REPLACEMENT_CHARACTER)
                i = bytes.size
                continue
            }

            val codePoint = decodeSequence(bytes, i, sequenceLength)
            if (codePoint == null) {
                out.append(REPLACEMENT_CHARACTER)
                i++
                continue
            }

            out.appendCodePoint(codePoint)
            i += sequenceLength
        }
        pending = ByteArray(0)
        return out.toString()
    }

    /** Decodes one [sequenceLength]-byte sequence starting at [start], or `null` if malformed. */
    private fun decodeSequence(bytes: ByteArray, start: Int, sequenceLength: Int): Int? {
        val b0 = bytes[start].toInt() and 0xFF
        var codePoint = when (sequenceLength) {
            2 -> b0 and 0x1F
            3 -> b0 and 0x0F
            else -> b0 and 0x07
        }
        for (offset in 1 until sequenceLength) {
            val b = bytes[start + offset].toInt() and 0xFF
            if (b and 0xC0 != 0x80) return null
            codePoint = (codePoint shl 6) or (b and 0x3F)
        }

        val minimumForLength = when (sequenceLength) {
            2 -> 0x80
            3 -> 0x800
            else -> 0x10000
        }
        if (codePoint < minimumForLength) return null // overlong encoding
        if (codePoint in 0xD800..0xDFFF) return null // surrogate half, invalid in UTF-8
        if (codePoint > 0x10FFFF) return null
        return codePoint
    }

    private fun StringBuilder.appendCodePoint(codePoint: Int): StringBuilder {
        if (codePoint <= 0xFFFF) {
            append(codePoint.toChar())
        } else {
            val adjusted = codePoint - 0x10000
            append(((adjusted shr 10) + 0xD800).toChar())
            append(((adjusted and 0x3FF) + 0xDC00).toChar())
        }
        return this
    }
}

/**
 * Splits [Utf8Decoder]'s chunk-boundary-safe decoded text into completed lines as raw logcat
 * stream bytes arrive. Handles both LF and CRLF line endings, delivers a final unterminated line
 * on [finish] rather than dropping it, and bounds an incomplete line that never sees a newline
 * (e.g. binary garbage on the stream) by force-flushing it once it exceeds [maxPendingLineLength]
 * — this is the one behavior with no equivalent in `:adapters-jvm`/`:adapters-adb`'s line decoders,
 * which assume a well-behaved shell/process stream and buffer an incomplete line unboundedly.
 * Downstream logcat line parsing decides whether a force-flushed line is a genuine record — this
 * decoder only ever hands back raw text lines. Not thread-safe: one instance per stream.
 */
class LogcatByteDecoder(private val maxPendingLineLength: Int = DEFAULT_MAX_PENDING_LINE_LENGTH) {
    private val utf8Decoder = Utf8Decoder()
    private val pendingLine = StringBuilder()

    fun decode(bytes: ByteArray): List<String> {
        pendingLine.append(utf8Decoder.decode(bytes))
        return extractLines(forceFlushOverflow = true)
    }

    fun finish(): List<String> {
        pendingLine.append(utf8Decoder.finish())
        val lines = extractLines(forceFlushOverflow = false).toMutableList()
        if (pendingLine.isNotEmpty()) {
            lines += pendingLine.toString()
            pendingLine.clear()
        }
        return lines
    }

    private fun extractLines(forceFlushOverflow: Boolean): List<String> {
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
        if (forceFlushOverflow && pendingLine.length > maxPendingLineLength) {
            lines += pendingLine.toString()
            pendingLine.clear()
        }
        return lines
    }

    companion object {
        const val DEFAULT_MAX_PENDING_LINE_LENGTH = 64 * 1024
    }
}
