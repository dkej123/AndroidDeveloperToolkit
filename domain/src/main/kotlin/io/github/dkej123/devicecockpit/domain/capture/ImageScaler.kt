package io.github.dkej123.devicecockpit.domain.capture

/** Resizes a PNG on the host (an `:adapters-jvm` port), e.g. a screenshot to 1 px = 1 dp for MCP. */
fun interface ImageScaler {
    /** The PNG scaled to [width]×[height], or null when [png] cannot be decoded. */
    fun scale(png: ByteArray, width: Int, height: Int): ByteArray?
}
