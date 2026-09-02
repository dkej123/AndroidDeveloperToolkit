package dev.acme.adbtoolbox.adapters.adb.ddmlib

/**
 * Splits [Utf8ChunkDecoder]'s chunk-boundary-safe decoded text into completed lines as ddmlib
 * output chunks arrive, for [DdmlibAdbTransport.executeStream]. A trailing partial line with no
 * final newline is only emitted by [finish], matching `:adapters-jvm`'s `IncrementalLineDecoder`
 * line-boundary handling. Not thread-safe: one instance per shell call.
 */
internal class Utf8LineDecoder {
    private val chunkDecoder = Utf8ChunkDecoder()
    private val pending = StringBuilder()

    fun decode(data: ByteArray, offset: Int, length: Int): List<String> {
        pending.append(chunkDecoder.decode(data, offset, length))
        return extractCompletedLines()
    }

    fun finish(): List<String> {
        pending.append(chunkDecoder.finish())
        val lines = extractCompletedLines().toMutableList()
        if (pending.isNotEmpty()) {
            lines += pending.toString()
            pending.clear()
        }
        return lines
    }

    private fun extractCompletedLines(): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        while (true) {
            val newlineIndex = pending.indexOf("\n", start)
            if (newlineIndex < 0) break
            var end = newlineIndex
            if (end > start && pending[end - 1] == '\r') end--
            lines += pending.substring(start, end)
            start = newlineIndex + 1
        }
        if (start > 0) pending.delete(0, start)
        return lines
    }
}
