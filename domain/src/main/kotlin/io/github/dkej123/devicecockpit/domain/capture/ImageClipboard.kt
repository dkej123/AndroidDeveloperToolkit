package io.github.dkej123.devicecockpit.domain.capture

/** Puts a PNG on the system clipboard (an `:intellij` port, task 061); false when the clipboard refused it. */
fun interface ImageClipboard {
    fun copyPng(png: ByteArray): Boolean
}
