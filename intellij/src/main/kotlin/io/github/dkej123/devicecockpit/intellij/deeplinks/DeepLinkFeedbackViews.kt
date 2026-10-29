package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.application.deeplinks.*
import io.github.dkej123.devicecockpit.intellij.ui.common.*
import java.awt.*
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/** Visual pieces added in design/DEEP_LINK_DIALOG.md handoff 7 (§2b, §3a, §5, §6). */
internal object DeepLinkTones {
    fun color(tone: BannerTone): Color = when (tone) {
        BannerTone.Success -> DeviceCockpitTheme.Colors.green
        BannerTone.Warning -> DeviceCockpitTheme.Colors.amber
        BannerTone.Error -> DeviceCockpitTheme.Colors.red
    }
    fun background(tone: BannerTone): Color = when (tone) {
        BannerTone.Success -> DeviceCockpitTheme.Colors.greenBg
        BannerTone.Warning -> DeviceCockpitTheme.Colors.amberBg
        BannerTone.Error -> DeviceCockpitTheme.Colors.redBg
    }
    fun symbol(tone: BannerTone) = when (tone) { BannerTone.Success -> "✓"; BannerTone.Warning -> "!"; BannerTone.Error -> "✕" }
    fun alpha(color: Color, alpha: Float) = Color(color.red, color.green, color.blue, (alpha * 255).toInt())
}

/** Round tone glyph: tone fill, `panel`-coloured ✓ / ! / ✕. */
internal class ToneGlyph(private val tone: BannerTone, private val size: Int, private val symbolSize: Float) : Icon {
    override fun getIconWidth() = JBUI.scale(size)
    override fun getIconHeight() = JBUI.scale(size)
    override fun paintIcon(c: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = DeepLinkTones.color(tone)
            g.fill(Ellipse2D.Float(x.toFloat(), y.toFloat(), iconWidth.toFloat(), iconHeight.toFloat()))
            g.color = DeviceCockpitTheme.Colors.panel
            g.font = DeepLinkVisuals.ui(symbolSize, true)
            val symbol = DeepLinkTones.symbol(tone)
            val metrics = g.fontMetrics
            g.drawString(symbol, x + (iconWidth - metrics.stringWidth(symbol)) / 2, y + (iconHeight - metrics.height) / 2 + metrics.ascent)
        } finally { g.dispose() }
    }
}

/** 10 px `textDim` spinner, one turn per 0.7 s (`adb-spin`). */
internal class DeepLinkSpinner : JComponent() {
    private var angle = 0f
    private val timer = Timer(35) { angle = (angle + 360f * 35 / 700) % 360; repaint() }
    init { preferredSize = Dimension(JBUI.scale(14), JBUI.scale(14)); minimumSize = preferredSize }
    override fun addNotify() { super.addNotify(); timer.start() }
    override fun removeNotify() { timer.stop(); super.removeNotify() }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = JBUIScale.scale(10f)
            val x = (width - size) / 2
            val y = (height - size) / 2
            g.stroke = BasicStroke(JBUIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = DeepLinkTones.alpha(DeviceCockpitTheme.Colors.textDim, 0.25f)
            g.draw(Ellipse2D.Float(x, y, size, size))
            g.color = DeviceCockpitTheme.Colors.textDim
            g.draw(Arc2D.Float(x, y, size, size, -angle, 100f, Arc2D.OPEN))
        } finally { g.dispose() }
    }
}

/** "Who handles this link?" (§2b): glyph · text · [tile · label · activity] · link that opens the popover. */
internal class DeepLinkHandlersRow(
    private val label: (String) -> String,
    private val tile: (String, Int) -> Icon,
    private val onPick: (String) -> Unit,
) : JPanel(BorderLayout(JBUI.scale(6), 0)) {
    private val glyph = JLabel()
    private val spinner = DeepLinkSpinner()
    private val text = DeepLinkVisuals.label("", 11f).apply { name = "deviceDeepLinkHandlersText" }
    private val appTile = JLabel()
    private val app = DeepLinkVisuals.label("", 11f, true, DeviceCockpitTheme.Colors.text).apply { name = "deviceDeepLinkHandlerApp" }
    private val activity = DeepLinkVisuals.label("", 10f).apply { name = "deviceDeepLinkHandlerActivity"; font = DeepLinkVisuals.mono(10f) }
    private val link = DeepLinkVisuals.button("", DesignButtonStyle.LINK) { showPopover() }.apply { name = "deviceDeepLinkHandlersLink" }
    private var current: HandlersState = HandlersState.Hidden
    var selectedPackage: () -> String = { "" }

    init {
        name = "deviceDeepLinkHandlers"
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.emptyTop(5)
        toolTipText = "Checked with cmd package query-activities, without launching. Updates as you type."
        preferredSize = Dimension(0, JBUI.scale(24 + 5))
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
            isOpaque = false
            listOf(spinner, glyph, text, appTile, app, activity).forEach(::add)
        }
        add(left, BorderLayout.CENTER)
        add(link, BorderLayout.EAST)
    }

    fun show(state: HandlersState) {
        current = state
        isVisible = state != HandlersState.Hidden
        spinner.isVisible = state == HandlersState.Checking
        glyph.isVisible = state is HandlersState.Ready
        text.isVisible = state != HandlersState.Hidden
        listOf(appTile, app, activity).forEach { it.isVisible = false }
        link.isVisible = false
        when (state) {
            HandlersState.Hidden -> Unit
            HandlersState.Checking -> { text.text = "Checking which apps handle this link…"; text.foreground = DeviceCockpitTheme.Colors.textDim }
            is HandlersState.Ready -> {
                val row = HandlersRow.of(state.handlers, selectedPackage(), label)
                glyph.icon = ToneGlyph(row.tone, 14, 9f)
                text.text = row.text
                text.foreground = if (row.tone == BannerTone.Error) DeviceCockpitTheme.Colors.red else DeviceCockpitTheme.Colors.textDim
                row.handler?.let { component ->
                    appTile.icon = tile(packageOf(component), 14)
                    app.text = label(packageOf(component))
                    activity.text = component.substringAfter('/')
                    listOf(appTile, app, activity).forEach { it.isVisible = true }
                }
                row.link?.let { link.text = it; link.preferredSize = Dimension(link.getFontMetrics(link.font).stringWidth(it), JBUI.scale(24)); link.isVisible = true }
            }
        }
        revalidate(); repaint()
    }

    private fun packageOf(component: String) = component.substringBefore('/')

    private fun showPopover() {
        val handlers = (current as? HandlersState.Ready)?.handlers ?: return
        val resolved = when (handlers) {
            is LinkHandlers.OpensDirectly -> handlers.handler
            is LinkHandlers.BrowserFallback -> handlers.browser
            is LinkHandlers.HandledBy -> handlers.handler
            else -> null
        }
        val selected = selectedPackage()
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = DeviceCockpitTheme.Colors.panel
            border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(DeviceCockpitTheme.Colors.borderStrong), JBUI.Borders.empty(6, 8))
        }
        lateinit var popup: com.intellij.openapi.ui.popup.JBPopup
        content.add(DeepLinkVisuals.label("query-activities · ${handlers.matches.size} matches for VIEW + BROWSABLE", 10f).apply {
            font = DeepLinkVisuals.mono(10f); alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(JBUI.Borders.customLineBottom(DeviceCockpitTheme.Colors.border), JBUI.Borders.emptyBottom(6))
        })
        handlers.matches.forEach { component ->
            val pkg = packageOf(component)
            content.add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT; border = JBUI.Borders.empty(5, 0)
                add(JLabel(tile(pkg, 16)), BorderLayout.WEST)
                add(JPanel(GridLayout(2, 1)).apply {
                    isOpaque = false
                    add(DeepLinkVisuals.label(label(pkg), 11.5f, true, DeviceCockpitTheme.Colors.text))
                    add(DeepLinkVisuals.label(component, 10f).apply { font = DeepLinkVisuals.mono(10f) })
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    val tag = when {
                        selected.isEmpty() && component == resolved -> "opens"
                        selected.isNotEmpty() && pkg == selected -> "selected"
                        else -> null
                    }
                    tag?.let { add(DeepLinkTag(it)) }
                    if (pkg != selected) add(DeepLinkVisuals.button("Open in this app", DesignButtonStyle.LINK) { onPick(pkg); popup.cancel() })
                }, BorderLayout.EAST)
            })
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(true).setCancelOnClickOutside(true).setShowBorder(false).createPopup()
        content.preferredSize = Dimension(width, content.preferredSize.height)
        popup.showUnderneathOf(this)
    }
}

/** "opens" / "selected": 10/600 `green`, 1 px `green` 40 % border, r3. */
private class DeepLinkTag(text: String) : JLabel(text) {
    init {
        font = DeepLinkVisuals.ui(10f, true); foreground = DeviceCockpitTheme.Colors.green
        border = JBUI.Borders.empty(1, 5)
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = DeepLinkTones.alpha(DeviceCockpitTheme.Colors.green, 0.4f)
            g.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(6), JBUI.scale(6))
        } finally { g.dispose() }
        super.paintComponent(graphics)
    }
}

/** A rounded box: tone background and a 1 px tone outline at [outlineAlpha]. */
internal open class ToneBox(var tone: BannerTone, private val radius: Int, private val outlineAlpha: Float) : JPanel() {
    init { isOpaque = false }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(radius * 2)
            g.color = DeepLinkTones.background(tone)
            g.fillRoundRect(0, 0, width, height, arc, arc)
            g.color = DeepLinkTones.alpha(DeepLinkTones.color(tone), outlineAlpha)
            g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally { g.dispose() }
    }
}

/** Wrapping, non-editable text that reports a width-dependent preferred height. */
internal fun wrappingText(size: Float, color: Color) = object : JTextArea() {
    init {
        isEditable = false; isFocusable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
        font = DeepLinkVisuals.ui(size); foreground = color; border = JBUI.Borders.empty()
    }
    override fun getPreferredSize(): Dimension {
        val width = (parent?.width ?: 0).takeIf { it > 0 } ?: JBUI.scale(420)
        setSize(width, Short.MAX_VALUE.toInt())
        return Dimension(JBUI.scale(40), super.getPreferredSize().height)
    }
}

/** §3a: specific app + http(s) — `-p` skips App Link verification. */
internal class DeepLinkAppLinkWarning(onRealTap: () -> Unit) : ToneBox(BannerTone.Warning, 5, 0.33f) {
    private val message = wrappingText(10.5f, DeviceCockpitTheme.Colors.text)
    init {
        name = "deviceDeepLinkAppLinkWarning"
        isVisible = false
        layout = BorderLayout(JBUI.scale(12), 0)
        border = JBUI.Borders.empty(6, 9)
        add(message, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(DeepLinkVisuals.button("Test as a real tap", DesignButtonStyle.LINK, onRealTap).apply { name = "deviceDeepLinkRealTap" }, BorderLayout.NORTH)
        }, BorderLayout.EAST)
    }
    fun show(appLabel: String?, host: String) {
        isVisible = appLabel != null
        if (appLabel != null) {
            message.text = "Open in $appLabel delivers this link even if the App Link isn’t verified. " +
                "On Android 12+ a real tap opens the browser unless $host is verified."
        }
        revalidate()
    }
}

/** §5: the last launch, between Command and the actions. */
internal class DeepLinkResultBanner(
    private val tile: (String, Int) -> Icon,
    private val onDismiss: () -> Unit,
    private val onAction: (BannerAction) -> Unit,
    private val onCopyOutput: (String) -> Unit,
) : ToneBox(BannerTone.Success, 6, 0.4f) {
    private val glyph = JLabel()
    private val appTile = JLabel()
    private val title = DeepLinkVisuals.label("", 11.5f, true, DeviceCockpitTheme.Colors.text).apply { name = "deviceDeepLinkResultTitle" }
    private val detail = wrappingText(11f, DeviceCockpitTheme.Colors.textDim)
    private val mono = DeepLinkVisuals.label("", 10.5f).apply { font = DeepLinkVisuals.mono(10.5f) }
    private val time = DeepLinkVisuals.label("", 10f).apply { font = DeepLinkVisuals.mono(10f) }
    private val action = DeepLinkVisuals.button("", DesignButtonStyle.LINK) { banner?.action?.let(onAction) }.apply { name = "deviceDeepLinkResultAction" }
    private var banner: LaunchBanner? = null
    private var raw = ""

    init {
        name = "deviceDeepLinkResult"
        isVisible = false
        layout = BorderLayout(JBUI.scale(9), 0)
        border = JBUI.Borders.empty(9, 10, 9, 8)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(9), 0)).apply {
            isOpaque = false; add(glyph); add(appTile)
        }, BorderLayout.WEST)
        add(JPanel().apply {
            isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS)
            listOf(title, detail, mono).forEach { (it as JComponent).alignmentX = Component.LEFT_ALIGNMENT; add(it) }
        }, BorderLayout.CENTER)
        val dismiss = DeepLinkVisuals.button("×", DesignButtonStyle.LINK, onDismiss).apply {
            name = "deviceDeepLinkResultDismiss"; foreground = DeviceCockpitTheme.Colors.textDim; toolTipText = "Dismiss"; font = DeepLinkVisuals.ui(13f)
        }
        val copyOutput = DeepLinkVisuals.button("Copy output", DesignButtonStyle.LINK) { onCopyOutput(raw) }.apply {
            name = "deviceDeepLinkCopyOutput"; foreground = DeviceCockpitTheme.Colors.textDim
            font = DeepLinkVisuals.ui(10.5f).deriveFont(mapOf(java.awt.font.TextAttribute.UNDERLINE to java.awt.font.TextAttribute.UNDERLINE_ON))
        }
        add(JPanel().apply {
            isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS)
            fun right(component: JComponent) = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false; add(component) }
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false; add(time); add(dismiss) })
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(right(action))
            add(right(copyOutput))
        }, BorderLayout.EAST)
    }

    fun show(banner: LaunchBanner?, atMillis: Long, raw: String) {
        this.banner = banner
        this.raw = raw
        isVisible = banner != null
        if (banner == null) { revalidate(); return }
        tone = banner.tone
        glyph.icon = ToneGlyph(banner.tone, 16, 10f)
        appTile.icon = banner.appPackage?.let { tile(it, 18) }
        appTile.isVisible = banner.appPackage != null
        title.text = banner.title
        detail.text = banner.detail
        detail.isVisible = banner.detail.isNotEmpty()
        mono.text = banner.mono.orEmpty()
        mono.toolTipText = banner.mono
        mono.isVisible = banner.mono != null
        time.text = "Last launch · " + DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()))
        action.text = when (banner.action) {
            is BannerAction.CopyVerifyCommand -> "Copy verify command"
            BannerAction.TurnOnColdStart -> "Turn on Cold start"
            null -> ""
        }
        action.preferredSize = Dimension(action.getFontMetrics(action.font).stringWidth(action.text), JBUI.scale(18))
        action.isVisible = banner.action != null
        revalidate(); repaint()
    }
}

/** §6 Cold start: 13×13 r3 box (`field` + `borderStrong`; checked = `accent` + white tick) + 11 px label. */
internal class DeepLinkCheckBox(text: String) : JToggleButton(text) {
    init {
        isOpaque = false; isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
        font = DeepLinkVisuals.ui(11f); foreground = DeviceCockpitTheme.Colors.text
        horizontalAlignment = SwingConstants.LEFT
        border = JBUI.Borders.emptyLeft(13 + 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(getFontMetrics(font).stringWidth(text) + JBUI.scale(13 + 6 + 2), JBUI.scale(24))
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = JBUI.scale(13)
            val y = (height - size) / 2
            val arc = JBUI.scale(6)
            g.color = if (isSelected) DeviceCockpitTheme.Colors.accent else DeviceCockpitTheme.Colors.field
            g.fillRoundRect(0, y, size, size, arc, arc)
            if (!isSelected) { g.color = DeviceCockpitTheme.Colors.borderStrong; g.drawRoundRect(0, y, size - 1, size - 1, arc, arc) }
            else {
                g.color = Color.WHITE
                g.stroke = BasicStroke(JBUIScale.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawPolyline(intArrayOf(JBUI.scale(3), JBUI.scale(5), JBUI.scale(10)), intArrayOf(y + JBUI.scale(7), y + JBUI.scale(9), y + JBUI.scale(4)), 3)
            }
        } finally { g.dispose() }
        super.paintComponent(graphics)
    }
}

