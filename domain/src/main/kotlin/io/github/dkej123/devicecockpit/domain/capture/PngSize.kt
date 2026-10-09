package io.github.dkej123.devicecockpit.domain.capture

/** Width and height of a PNG from its IHDR chunk, without decoding it (task 066). */
object PngSize {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val IHDR = "IHDR"

    fun of(png: ByteArray): Pair<Int, Int>? {
        if (png.size < 24 || !png.copyOfRange(0, 8).contentEquals(SIGNATURE)) return null
        if (png.copyOfRange(12, 16).decodeToString() != IHDR) return null
        val width = int(png, 16)
        val height = int(png, 20)
        return if (width > 0 && height > 0) width to height else null
    }

    private fun int(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
            (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)
}
