package io.github.dkej123.devicecockpit.intellij.mcp

import com.intellij.openapi.ide.CopyPasteManager
import io.github.dkej123.devicecockpit.domain.capture.ImageClipboard
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** [ImageClipboard] on the IDE clipboard (task 061): the PNG is decoded off the EDT, set on it. */
class IdeImageClipboard : ImageClipboard {
    override fun copyPng(png: ByteArray): Boolean {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return false
        val transferable = ImageTransferable(image)
        return runCatching {
            if (SwingUtilities.isEventDispatchThread()) {
                CopyPasteManager.getInstance().setContents(transferable)
            } else {
                SwingUtilities.invokeAndWait { CopyPasteManager.getInstance().setContents(transferable) }
            }
            true
        }.getOrDefault(false)
    }

    private class ImageTransferable(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}
