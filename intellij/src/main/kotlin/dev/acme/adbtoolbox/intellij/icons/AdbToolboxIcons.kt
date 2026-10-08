package dev.acme.adbtoolbox.intellij.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * IntelliJ-loaded production icons supplied under `design/icons/expui` (icon set v2, 2026-10-01).
 * Strokes use the exact New UI palette (`#6C707E` / `#CED0D6`) so the IDE recolors them on
 * selection; `_dark` and the tool window's `@20x20` siblings are picked up by [IconLoader].
 * Refresh, pause/resume, autoscroll, wrap, filter and search come from `AllIcons` instead.
 */
object AdbToolboxIcons {
    val toolWindow: Icon get() = load("/icons/expui/toolwindow/adbToolbox.svg")

    object Views {
        val device: Icon get() = load("/icons/expui/views/device.svg")
        val apps: Icon get() = load("/icons/expui/views/apps.svg")
        val network: Icon get() = load("/icons/expui/views/network.svg")
        val logcat: Icon get() = load("/icons/expui/views/logcat.svg")
    }

    object Actions {
        val authorize: Icon get() = load("/icons/expui/actions/authorize.svg")
        val clearData: Icon get() = load("/icons/expui/actions/clearData.svg")
        val density: Icon get() = load("/icons/expui/actions/density.svg")
        val fontScale: Icon get() = load("/icons/expui/actions/fontScale.svg")
        val forceStop: Icon get() = load("/icons/expui/actions/forceStop.svg")
        val mirror: Icon get() = load("/icons/expui/actions/mirror.svg")
        val options: Icon get() = load("/icons/expui/actions/options.svg")
        val pin: Icon get() = load("/icons/expui/actions/pin.svg")
        val pinned: Icon get() = load("/icons/expui/actions/pinned.svg")
        val proxy: Icon get() = load("/icons/expui/actions/proxy.svg")
        val record: Icon get() = load("/icons/expui/actions/record.svg")
        val resetOverrides: Icon get() = load("/icons/expui/actions/resetOverrides.svg")
        val restartApp: Icon get() = load("/icons/expui/actions/restartApp.svg")
        val screenshot: Icon get() = load("/icons/expui/actions/screenshot.svg")
        val stopRecording: Icon get() = load("/icons/expui/actions/stopRecording.svg")
        val uninstall: Icon get() = load("/icons/expui/actions/uninstall.svg")
        val usb: Icon get() = load("/icons/expui/actions/usb.svg")
        val wifi: Icon get() = load("/icons/expui/actions/wifi.svg")
        val layoutInspector: Icon get() = load("/icons/expui/actions/layoutInspector.svg")
        val a11yAudit: Icon get() = load("/icons/expui/actions/a11yAudit.svg")
        val overlay: Icon get() = load("/icons/expui/actions/overlay.svg")
        val location: Icon get() = load("/icons/expui/actions/location.svg")
        val language: Icon get() = load("/icons/expui/actions/language.svg")
        val mcpAgent: Icon get() = load("/icons/expui/actions/mcpAgent.svg")
        val rotate: Icon get() = load("/icons/expui/actions/rotate.svg")
    }

    /** Complete loadable catalog, used by the package test and future feature call sites. */
    val all: List<Icon> get() = listOf(
        toolWindow,
        Views.device,
        Views.apps,
        Views.network,
        Views.logcat,
        Actions.authorize,
        Actions.clearData,
        Actions.density,
        Actions.fontScale,
        Actions.forceStop,
        Actions.mirror,
        Actions.options,
        Actions.pin,
        Actions.pinned,
        Actions.proxy,
        Actions.record,
        Actions.resetOverrides,
        Actions.restartApp,
        Actions.screenshot,
        Actions.stopRecording,
        Actions.uninstall,
        Actions.usb,
        Actions.wifi,
        Actions.layoutInspector,
        Actions.a11yAudit,
        Actions.overlay,
        Actions.location,
        Actions.language,
        Actions.mcpAgent,
        Actions.rotate,
    )

    /**
     * [icon] painted in solid [color] with its own alpha kept — the selected rail glyph. Unlike
     * `IconUtil.colorize`, which blends by brightness and leaves the glyph dimmer than `accent`.
     */
    fun tinted(icon: Icon, color: java.awt.Color): Icon = TintedIcon(icon, color)

    /**
     * Paints [icon] into an offscreen buffer at the target's device scale (so it stays sharp on
     * HiDPI), then fills it with [color] through `SrcIn`, which keeps each pixel's alpha. Plain AWT
     * on purpose: `IconLoader.filterIcon`/`RgbImageFilterSupplier` are internal platform API.
     */
    private class TintedIcon(private val icon: Icon, private val color: java.awt.Color) : Icon {
        override fun getIconWidth(): Int = icon.iconWidth

        override fun getIconHeight(): Int = icon.iconHeight

        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
            val width = iconWidth
            val height = iconHeight
            if (width <= 0 || height <= 0) return
            val scale = (g as? java.awt.Graphics2D)?.transform?.scaleX?.takeIf { it > 0 } ?: 1.0
            val buffer = java.awt.image.BufferedImage(
                kotlin.math.ceil(width * scale).toInt(),
                kotlin.math.ceil(height * scale).toInt(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB,
            )
            val bg = buffer.createGraphics()
            try {
                bg.scale(scale, scale)
                icon.paintIcon(c, bg, 0, 0)
                bg.composite = java.awt.AlphaComposite.SrcIn
                bg.color = color
                bg.fillRect(0, 0, width, height)
            } finally {
                bg.dispose()
            }
            g.drawImage(buffer, x, y, width, height, null)
        }
    }

    private fun load(path: String): Icon = requireNotNull(IconLoader.getIcon(path, AdbToolboxIcons::class.java)) {
        "Missing icon resource: $path"
    }
}
