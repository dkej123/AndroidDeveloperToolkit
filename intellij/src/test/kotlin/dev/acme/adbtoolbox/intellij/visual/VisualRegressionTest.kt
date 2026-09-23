package dev.acme.adbtoolbox.intellij.visual

import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewState
import dev.acme.adbtoolbox.application.apps.AppsRow
import dev.acme.adbtoolbox.application.apps.AppsViewState
import dev.acme.adbtoolbox.application.apps.ClearDataViewState
import dev.acme.adbtoolbox.application.apps.UninstallViewState
import dev.acme.adbtoolbox.application.devicebar.DeviceBarPresentation
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewState
import dev.acme.adbtoolbox.application.display.QuickToggleFieldState
import dev.acme.adbtoolbox.application.display.QuickTogglesViewState
import dev.acme.adbtoolbox.application.display.density.DensityViewState
import dev.acme.adbtoolbox.application.logcat.LogcatControlsState
import dev.acme.adbtoolbox.application.network.ProxyViewState
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.display.AnimationsSummary
import dev.acme.adbtoolbox.domain.display.density.DensityReading
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState
import dev.acme.adbtoolbox.domain.feedback.ProcessIndicator
import dev.acme.adbtoolbox.domain.feedback.StatusState
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.domain.logcat.LogcatPauseState
import dev.acme.adbtoolbox.domain.logcat.LogcatUnseenState
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyHost
import dev.acme.adbtoolbox.domain.network.ProxyHostResult
import dev.acme.adbtoolbox.domain.network.ProxyPort
import dev.acme.adbtoolbox.domain.network.ProxyPortResult
import dev.acme.adbtoolbox.domain.network.ProxyReadState
import dev.acme.adbtoolbox.intellij.apps.AppsPanel
import dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarPanel
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import dev.acme.adbtoolbox.intellij.display.DisplayPanel
import dev.acme.adbtoolbox.intellij.feedback.FeedbackStatusPanel
import dev.acme.adbtoolbox.intellij.logcat.LogcatMatchSpan
import dev.acme.adbtoolbox.intellij.logcat.LogcatPanel
import dev.acme.adbtoolbox.intellij.logcat.LogcatRenderBatch
import dev.acme.adbtoolbox.intellij.logcat.LogcatRenderRow
import dev.acme.adbtoolbox.intellij.logcat.LogcatVirtualList
import dev.acme.adbtoolbox.intellij.nav.NavigationRailPanel
import dev.acme.adbtoolbox.intellij.network.NetworkPanel
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * Task 055's screenshot gate. These are production Swing components rendered by the real IntelliJ
 * test sandbox, not a parallel HTML/mock implementation. The matrix deliberately crosses all five
 * primary views, global chrome, light/dark themes, and the supplied narrow/dock/wide width classes.
 * Behavioral assertions remain in the component/journey suites; a screenshot is additional design
 * evidence, never the sole proof that a control works.
 */
class VisualRegressionTest : BasePlatformTestCase() {

    fun `test reviewed design matrix matches production rendering`() {
        val failures = mutableListOf<String>()
        val wasDark = !JBColor.isBright()
        val originalUiDefaults = HEADLESS_THEME_KEYS.associateWith(UIManager::get)
        try {
            // Other platform tests can toggle the global loader. Production renders with it active,
            // and the visual gate must not depend on class/test execution order.
            IconLoader.activate()
            scenarios().forEach { scenario ->
                val image = render(scenario)
                GoldenImages.verify(scenario.name, image)?.let(failures::add)
            }
        } finally {
            originalUiDefaults.forEach { (key, value) -> UIManager.put(key, value) }
            JBColor.setDark(wasDark)
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    private fun scenarios(): List<Scenario> = listOf(
        Scenario("device-empty-light-narrow", 300, 620, dark = false, selected = ViewId.Device, view = {
            DeviceFactsPanel(onCopyReport = {}).apply {
                applyResponsiveLayout(300)
                update(DeviceFactsViewState.NoDevice)
            }
        }, noDevice = true),
        Scenario("device-connected-dark-dock", 380, 620, dark = true, selected = ViewId.Device, view = {
            DeviceViewFixture.connected(SERIAL).apply { applyResponsiveLayout(380 - AdbToolboxTheme.Sizes.rail) }
        }),
        Scenario("apps-dark-dock", 380, 620, dark = true, selected = ViewId.Apps, view = {
            AppsPanel({}, {}, {}, {}).apply {
                update(
                    AppsViewState(
                        hasDevice = true,
                        isLoading = false,
                        rows = listOf(
                            AppsRow("com.acme.shop", "Acme Shop", true, true, true),
                            AppsRow("com.acme.wallet", "Wallet", true, false, false),
                            AppsRow("com.acme.debug", "Debug tools", true, true, false),
                        ),
                        selectedPackageName = "com.acme.shop",
                    ),
                )
                updateLifecycle(AppLifecycleViewState(ControlPolicy.Enabled, "com.acme.shop", busy = false))
                updateClearData(ClearDataViewState(ControlPolicy.Enabled, "com.acme.shop", busy = false))
                updateUninstall(UninstallViewState(ControlPolicy.Enabled, "com.acme.shop", busy = false))
            }
        }),
        Scenario("display-light-dock", 380, 620, dark = false, selected = ViewId.Display, view = {
            DisplayPanel({}, {}, {}, {}, {}, {}, {}, {}).apply {
                update(FontScaleState.Idle(1.15))
                update(DensityViewState.Idle(DensityReading(428, 535)))
                update(
                    QuickTogglesViewState(
                        darkTheme = QuickToggleFieldState.Idle(true),
                        showTouches = QuickToggleFieldState.Idle(false),
                        animations = QuickToggleFieldState.Idle(AnimationsSummary.AllOff),
                    ),
                )
            }
        }, overrideCount = 2),
        Scenario("network-dark-dock", 380, 620, dark = true, selected = ViewId.Network, view = {
            val active = endpoint("10.0.4.117", 8888)
            NetworkPanel({}, {}, {}, {}, {}, {}).apply {
                update(
                    ProxyViewState(
                        serial = SERIAL,
                        isDeviceEligible = true,
                        readState = ProxyReadState.Active(active),
                        hostInput = "10.0.4.117",
                        portInput = "8888",
                        recents = listOf(active, endpoint("10.0.4.117", 8080), endpoint("proxy.acme.dev", 3128)),
                    ),
                )
            }
        }, overrideCount = 1),
        Scenario("logcat-dark-wide", 560, 620, dark = true, selected = ViewId.Logcat, view = {
            logcatPanel(560)
        }, process = "scrcpy"),
    )

    private fun render(scenario: Scenario): BufferedImage {
        var result: BufferedImage? = null
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            JBColor.setDark(scenario.dark)
            applyHeadlessThemeDefaults()
            val deviceBar = DeviceContextBarPanel({}, {}).apply {
                applyResponsiveLayout(scenario.width)
                update(if (scenario.noDevice) DeviceBarPresentation.NoDevice else onlinePresentation())
            }
            val rail = NavigationRailPanel().apply {
                setSelected(scenario.selected)
                updateBadges(buildMap {
                    if (scenario.overrideCount > 0) put(ViewId.Display, NavigationBadge.Count(1))
                    if (scenario.selected == ViewId.Logcat) put(ViewId.Logcat, NavigationBadge.Attention)
                })
            }
            val status = FeedbackStatusPanel().apply {
                update(StatusState(process = scenario.process?.let(ProcessIndicator::InProgress) ?: ProcessIndicator.Idle, message = "Ready"))
                updateOverrideCount(scenario.overrideCount)
            }
            val root = JPanel(BorderLayout()).apply {
                background = AdbToolboxTheme.Colors.bg
                add(deviceBar, BorderLayout.NORTH)
                add(rail, BorderLayout.WEST)
                add(scenario.view(), BorderLayout.CENTER)
                add(status, BorderLayout.SOUTH)
            }
            result = paint(root, scenario.width, scenario.height)
        }
        return requireNotNull(result)
    }

    private fun paint(component: JComponent, width: Int, height: Int): BufferedImage {
        component.setSize(width, height)
        recursivelyDisableDoubleBuffering(component)
        // Complex JScrollPane/JViewport trees and width-dependent wrapping text settle through a
        // short sequence of queued revalidations in a live IDE. Repeating an invalidate + layout
        // pass (so layout managers drop cached size requirements, as `revalidate()` does; a
        // peer-less tree ignores `validate()`) gives the off-screen test the same stable final
        // geometry without timing or sleeps.
        repeat(4) {
            recursivelyInvalidate(component)
            recursivelyLayout(component)
        }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = component.background ?: Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            component.printAll(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun recursivelyLayout(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::recursivelyLayout)
        }
    }

    private fun recursivelyInvalidate(component: Component) {
        component.invalidate()
        if (component is Container) component.components.forEach(::recursivelyInvalidate)
    }

    private fun recursivelyDisableDoubleBuffering(component: Component) {
        if (component is JComponent) component.isDoubleBuffered = false
        if (component is Container) component.components.forEach(::recursivelyDisableDoubleBuffering)
    }

    private fun logcatPanel(width: Int): LogcatPanel {
        val list = LogcatVirtualList()
        list.virtualModel.apply(
            LogcatRenderBatch(
                rows = listOf(
                    LogcatRenderRow(1, LogSeverity.INFO, "09-23 10:41:02.101", "MainActivity", "Application started"),
                    LogcatRenderRow(2, LogSeverity.DEBUG, "09-23 10:41:02.140", "Network", "GET /api/catalog 200"),
                    LogcatRenderRow(3, LogSeverity.WARN, "09-23 10:41:03.008", "RenderThread", "Skipped 12 frames"),
                    LogcatRenderRow(4, LogSeverity.ERROR, "09-23 10:41:04.512", "Checkout", "Request timeout", listOf(LogcatMatchSpan(8, 15))),
                    LogcatRenderRow(5, LogSeverity.ASSERT, "09-23 10:41:04.700", "AndroidRuntime", "Fatal assertion in payment flow"),
                ),
                reset = true,
            ),
        )
        return LogcatPanel(list, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}).apply {
            applyResponsiveColumns(width)
            update(
                LogcatControlsState(
                    serial = SERIAL,
                    isDeviceEligible = true,
                    query = "timeout",
                    minSeverity = LogSeverity.DEBUG,
                    packageFilterOn = true,
                    packageFilterLabel = "com.acme.shop",
                    pauseState = LogcatPauseState.Paused(5),
                    follow = false,
                    unseen = LogcatUnseenState(paused = true, unseenCount = 234, viewTruncated = false),
                    visibleCount = 5,
                    totalRetainedCount = 1_482,
                    totalBytes = 16 * 1024 * 1024,
                ),
            )
        }
    }

    private data class Scenario(
        val name: String,
        val width: Int,
        val height: Int,
        val dark: Boolean,
        val selected: ViewId,
        val view: () -> JComponent,
        val noDevice: Boolean = false,
        val overrideCount: Int = 0,
        val process: String? = null,
    )

    private companion object {
        val HEADLESS_THEME_KEYS = listOf(
            "Panel.background",
            "Viewport.background",
            "ScrollPane.background",
            "List.background",
            "List.foreground",
            "TextField.background",
            "TextField.foreground",
            "Label.foreground",
            "Button.background",
            "Button.foreground",
            "ToggleButton.background",
            "ToggleButton.foreground",
            "Label.font",
            "Button.font",
            "ToggleButton.font",
            "TextField.font",
            "List.font",
        )

        val SERIAL = DeviceSerial.of("R58N90ABCDE")

        fun applyHeadlessThemeDefaults() {
            fun snapshot(color: Color) = Color(color.rgb, true)
            val bg = snapshot(AdbToolboxTheme.Colors.bg)
            val panel = snapshot(AdbToolboxTheme.Colors.panel)
            val field = snapshot(AdbToolboxTheme.Colors.field)
            val text = snapshot(AdbToolboxTheme.Colors.text)
            UIManager.put("Panel.background", panel)
            UIManager.put("Viewport.background", bg)
            UIManager.put("ScrollPane.background", bg)
            UIManager.put("List.background", bg)
            UIManager.put("List.foreground", text)
            UIManager.put("TextField.background", field)
            UIManager.put("TextField.foreground", text)
            UIManager.put("Label.foreground", text)
            UIManager.put("Button.background", panel)
            UIManager.put("Button.foreground", text)
            UIManager.put("ToggleButton.background", panel)
            UIManager.put("ToggleButton.foreground", text)
            // The bare test sandbox runs Metal, whose default control font is bold 12pt. IntelliJ's
            // New UI label font is regular 13pt; render with that so the goldens show the IDE's
            // text weight rather than a headless look-and-feel artifact.
            val ideLabelFont = javax.swing.plaf.FontUIResource(java.awt.Font.DIALOG, java.awt.Font.PLAIN, 13)
            listOf("Label.font", "Button.font", "ToggleButton.font", "TextField.font", "List.font")
                .forEach { key -> UIManager.put(key, ideLabelFont) }
        }

        fun onlinePresentation() = DeviceBarPresentation.Online(
            Device(serial = SERIAL, state = DeviceConnectionState.Online, model = "Pixel 8 Pro"),
            onlineCount = 3,
        )


        fun endpoint(host: String, port: Int) = ProxyEndpoint(
            (ProxyHost.parse(host) as ProxyHostResult.Valid).host,
            (ProxyPort.parse(port) as ProxyPortResult.Valid).port,
        )
    }
}

private object GoldenImages {
    private const val CHANNEL_TOLERANCE = 12
    private const val MAX_DIFFERENT_PIXEL_RATIO = 0.0025

    fun verify(name: String, actual: BufferedImage): String? {
        val moduleDir = locateModuleDir()
        val golden = moduleDir.resolve("src/test/resources/visual/$name.png")
        val reportDir = moduleDir.resolve("build/reports/visual")
        Files.createDirectories(reportDir)
        val actualPath = reportDir.resolve("$name-actual.png")
        ImageIO.write(actual, "png", actualPath.toFile())

        if (System.getProperty("adbtoolbox.updateVisualGoldens") == "true") {
            Files.createDirectories(golden.parent)
            ImageIO.write(actual, "png", golden.toFile())
            return null
        }
        if (!Files.exists(golden)) {
            return "Missing reviewed golden for $name. Candidate: $actualPath"
        }

        val expected = ImageIO.read(golden.toFile())
        if (expected.width != actual.width || expected.height != actual.height) {
            return "$name size changed: expected ${expected.width}x${expected.height}, " +
                "actual ${actual.width}x${actual.height}. Candidate: $actualPath"
        }

        var different = 0L
        val diff = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                val expectedRgb = expected.getRGB(x, y)
                val actualRgb = actual.getRGB(x, y)
                val isDifferent = channelDelta(expectedRgb, actualRgb) > CHANNEL_TOLERANCE
                if (isDifferent) different++
                diff.setRGB(x, y, if (isDifferent) 0xffff2d55.toInt() else dim(actualRgb))
            }
        }
        val ratio = different.toDouble() / (actual.width.toLong() * actual.height.toLong())
        if (ratio <= MAX_DIFFERENT_PIXEL_RATIO) return null

        val diffPath = reportDir.resolve("$name-diff.png")
        ImageIO.write(diff, "png", diffPath.toFile())
        return "$name differs in ${"%.3f".format(ratio * 100)}% of pixels " +
            "(allowed ${MAX_DIFFERENT_PIXEL_RATIO * 100}%). Expected: $golden; actual: $actualPath; diff: $diffPath"
    }

    private fun channelDelta(a: Int, b: Int): Int = maxOf(
        kotlin.math.abs((a ushr 24 and 0xff) - (b ushr 24 and 0xff)),
        kotlin.math.abs((a ushr 16 and 0xff) - (b ushr 16 and 0xff)),
        kotlin.math.abs((a ushr 8 and 0xff) - (b ushr 8 and 0xff)),
        kotlin.math.abs((a and 0xff) - (b and 0xff)),
    )

    private fun dim(rgb: Int): Int {
        val alpha = rgb ushr 24 and 0xff
        val red = (rgb ushr 16 and 0xff) / 4
        val green = (rgb ushr 8 and 0xff) / 4
        val blue = (rgb and 0xff) / 4
        return alpha shl 24 or (red shl 16) or (green shl 8) or blue
    }

    private fun locateModuleDir(): Path {
        val cwd = Path.of("").toAbsolutePath().normalize()
        return when {
            Files.isDirectory(cwd.resolve("src/test")) -> cwd
            Files.isDirectory(cwd.resolve("intellij/src/test")) -> cwd.resolve("intellij")
            else -> error("Cannot locate the intellij module from $cwd")
        }
    }
}
