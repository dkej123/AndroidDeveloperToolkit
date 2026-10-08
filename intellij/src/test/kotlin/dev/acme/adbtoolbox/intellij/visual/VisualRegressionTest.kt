package dev.acme.adbtoolbox.intellij.visual

import dev.acme.adbtoolbox.domain.device.DeviceConnectionKind
import dev.acme.adbtoolbox.intellij.devicebar.DevicePickerListPanel
import dev.acme.adbtoolbox.application.devicebar.DevicePickerState
import dev.acme.adbtoolbox.application.devicebar.DevicePickerItem
import dev.acme.adbtoolbox.domain.display.TalkBackProfile

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
import dev.acme.adbtoolbox.application.mirroring.ScrcpyAvailability
import dev.acme.adbtoolbox.application.display.developer.DeveloperOptionsViewState
import dev.acme.adbtoolbox.application.display.developer.DeveloperToggle
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
        val originalTimeZone = java.util.TimeZone.getDefault()
        try {
            // The inspector shows the capture time; CI runs in UTC, so render every golden in UTC.
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            // Goldens show every section expanded, whatever an earlier test or run left collapsed.
            com.intellij.ide.util.PropertiesComponent.getInstance()
                .unsetValue(dev.acme.adbtoolbox.intellij.ui.common.CollapsedSectionsStore.KEY)
            // Other platform tests can toggle the global loader. Production renders with it active,
            // and the visual gate must not depend on class/test execution order.
            IconLoader.activate()
            scenarios().forEach { scenario ->
                val image = render(scenario)
                GoldenImages.verify(scenario.name, image)?.let(failures::add)
            }
        } finally {
            originalUiDefaults.forEach { (key, value) -> UIManager.put(key, value) }
            java.util.TimeZone.setDefault(originalTimeZone)
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
        // Design handoff 4 §1 QA: compare with design/screenshots/device-picker.png.
        Scenario("device-picker-dark-wide", 560, 196, dark = true, selected = ViewId.Device, chrome = false, view = {
            fun item(serial: String, model: String, state: DeviceConnectionState, selected: Boolean = false) =
                DevicePickerItem(
                    serial = DeviceSerial.of(serial),
                    model = model,
                    product = null,
                    connectionKind = DeviceConnectionKind.of(serial),
                    connectionState = state,
                    isSelected = selected,
                )
            val picker = DevicePickerListPanel({}, {}, {}, {}, {}).apply {
                update(
                    DevicePickerState(
                        isOpen = true,
                        items = listOf(
                            item("49060DLAQ002W7", "Pixel 9", DeviceConnectionState.Online, selected = true),
                            item("10.0.4.91:5555", "Pixel Tablet", DeviceConnectionState.Online),
                            item("R5CT90XKPQZ", "Galaxy S23", DeviceConnectionState.Unauthorized),
                            item("emulator-5554", "Pixel 9 API 37", DeviceConnectionState.Online),
                        ),
                        highlightedIndex = 0,
                    ),
                )
            }
            JPanel(BorderLayout()).apply {
                background = AdbToolboxTheme.Colors.bg
                border = javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8)
                add(picker, BorderLayout.NORTH)
            }
        }),
        Scenario("device-screen-active-light-dock", 380, 620, dark = false, selected = ViewId.Device, view = {
            DeviceViewFixture.connected(SERIAL, active = true).apply { applyResponsiveLayout(380 - AdbToolboxTheme.Sizes.rail) }
        }),
        Scenario("device-scrcpy-missing-light-dock", 380, 620, dark = false, selected = ViewId.Device, view = {
            val missing = ScrcpyAvailability.Missing(
                reason = "scrcpy is not installed, or not on PATH.",
                installCommand = "brew install scrcpy",
                configuredPathInvalid = false,
            )
            DeviceViewFixture.connected(SERIAL, scrcpy = missing).apply { applyResponsiveLayout(380 - AdbToolboxTheme.Sizes.rail) }
        }),
        Scenario("apps-dark-dock", 380, 620, dark = true, selected = ViewId.Apps, view = {
            AppsPanel({}, {}, {}, {}).apply {
                update(
                    AppsViewState(
                        hasDevice = true,
                        isLoading = false,
                        rows = listOf(
                            AppsRow("com.acme.wallet", "Wallet", true, false, false, isPinned = true, sectionHeader = "Pinned"),
                            AppsRow("com.acme.shop", "Acme Shop", true, true, true, sectionHeader = "All apps"),
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
        Scenario("display-light-dock", 380, 620, dark = false, selected = ViewId.Device, view = {
            DisplayPanel({}, {}, {}, {}, {}, {}, {}, {}).apply {
                update(FontScaleState.Idle(1.15))
                update(DensityViewState.Idle(DensityReading(428, 535)))
                update(
                    QuickTogglesViewState(
                        darkTheme = QuickToggleFieldState.Idle(true),
                        showTouches = QuickToggleFieldState.Idle(false),
                        animations = QuickToggleFieldState.Idle(AnimationsSummary.AllOff),
                        talkBack = QuickToggleFieldState.Idle(false),
                        talkBackProfile = TalkBackProfile.Samsung,
                    ),
                )
                update(developerOptionsFixture())
                update(settingTogglesFixture())
            }
        }, overrideCount = 2),
        // Taller, so every toggle group, rotation and the process-limit chips are in the golden too.
        Scenario("display-toggles-light-tall", 380, 1000, dark = false, selected = ViewId.Device, view = {
            DisplayPanel({}, {}, {}, {}, {}, {}, {}, {}).apply {
                update(FontScaleState.Idle(1.0))
                update(DensityViewState.Idle(DensityReading(428, null)))
                update(
                    QuickTogglesViewState(
                        darkTheme = QuickToggleFieldState.Idle(false),
                        showTouches = QuickToggleFieldState.Idle(true),
                        animations = QuickToggleFieldState.Idle(AnimationsSummary.AllOn),
                        talkBack = QuickToggleFieldState.Idle(false),
                        talkBackProfile = TalkBackProfile.Google,
                    ),
                )
                update(developerOptionsFixture())
                update(settingTogglesFixture())
            }
        }),
        // Current app with a debuggable app in front (design §3a).
        Scenario("device-current-app-dark-dock", 380, 360, dark = true, selected = ViewId.Device, view = {
            dev.acme.adbtoolbox.intellij.currentapp.CurrentAppSection(
                identity = { dev.acme.adbtoolbox.intellij.currentapp.AppIdentity("Acme Shop", null) },
                onAction = { _, _ -> }, onDetails = {}, onRefresh = {}, onApplyPending = {}, onWake = {}, onLaunchLast = {},
                now = { 2_000L },
            ).apply {
                val app = dev.acme.adbtoolbox.domain.foreground.ForegroundState.App("com.acme.shop", ".checkout.CheckoutActivity")
                update(
                    dev.acme.adbtoolbox.application.currentapp.CurrentAppViewState(
                        display = dev.acme.adbtoolbox.application.currentapp.CurrentAppDisplay.App(
                            snapshot = dev.acme.adbtoolbox.application.currentapp.CurrentAppSnapshot(
                                foreground = app,
                                details = dev.acme.adbtoolbox.domain.foreground.PackageDetails("4.12.0-dev", 41200, 26, 36, debuggable = true, system = false, runtimePermissions = emptyList()),
                                process = dev.acme.adbtoolbox.domain.foreground.ProcessInfo(8155, kotlin.time.Duration.parse("3m 12s")),
                            ),
                            app = app,
                            killed = false,
                        ),
                        updatedAtMillis = 0L,
                        deviceOnline = true,
                    ),
                )
            }
        }),
        // Language & region with an override and Location on an emulator (design §3b, §3c).
        Scenario("device-locale-location-dark-dock", 380, 620, dark = true, selected = ViewId.Device, view = {
            javax.swing.JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                background = dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Colors.bg
                add(dev.acme.adbtoolbox.intellij.display.LocaleSection({}, {}, {}).apply {
                    update(
                        dev.acme.adbtoolbox.application.locale.LocaleViewState(
                            locale = dev.acme.adbtoolbox.application.locale.DeviceLocaleState("ar-XB", "en-US"),
                            loading = false,
                        ),
                        deviceOnline = true,
                    )
                })
                add(dev.acme.adbtoolbox.intellij.display.LocationSection({}, { _, _ -> }).apply {
                    update(
                        dev.acme.adbtoolbox.application.locale.LocationViewState(
                            emulator = true,
                            current = "Warsaw" to dev.acme.adbtoolbox.domain.location.GeoPoint.of(52.2297, 21.0122)!!,
                        ),
                        deviceOnline = true,
                    )
                })
                add(javax.swing.Box.createVerticalGlue())
            }
        }, overrideCount = 1),
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
        // The Layout Inspector is an editor tab, so it renders without the tool-window chrome.
        Scenario("inspector-light-wide", 1300, 820, dark = false, selected = ViewId.Device, chrome = false, view = {
            inspectorPanel(audit = false)
        }),
        Scenario("inspector-audit-dark-wide", 1300, 820, dark = true, selected = ViewId.Device, chrome = false, view = {
            inspectorPanel(audit = true)
        }),
    )

    /** The API 30 Settings capture with Display selected and Battery hovered (a 51 dp redline), grid on. */
    private fun inspectorPanel(audit: Boolean): JComponent {
        val capture = dev.acme.adbtoolbox.intellij.inspector.settingsCapture()
        val panel = dev.acme.adbtoolbox.intellij.inspector.LayoutInspectorPanel(onRecapture = {}, onOpenQuickToggles = {}, chooseOverlayFile = { null })
        panel.update(dev.acme.adbtoolbox.application.layout.LayoutInspectorState(capture = capture, deviceOnline = true, deviceName = "Pixel 9"))
        val nodes = capture.snapshot.hierarchy.root.descendantsAndSelf().toList()
        panel.gridChipForTest.doClick()
        panel.selectForTest(nodes.first { it.text == "Display" })
        panel.hoverForTest(nodes.first { it.text == "Battery" })
        panel.canvasForTest.hovered = nodes.first { it.text == "Battery" }
        if (audit) panel.auditChipForTest.doClick()
        return panel
    }

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
                    if (scenario.overrideCount > 0) put(ViewId.Device, NavigationBadge.Count(1))
                    if (scenario.selected == ViewId.Logcat) put(ViewId.Logcat, NavigationBadge.Attention)
                })
            }
            val status = FeedbackStatusPanel().apply {
                update(StatusState(process = scenario.process?.let(ProcessIndicator::InProgress) ?: ProcessIndicator.Idle, message = "Ready"))
                updateOverrideCount(scenario.overrideCount)
            }
            val root = JPanel(BorderLayout()).apply {
                background = AdbToolboxTheme.Colors.bg
                if (scenario.chrome) {
                    add(deviceBar, BorderLayout.NORTH)
                    add(rail, BorderLayout.WEST)
                    add(status, BorderLayout.SOUTH)
                }
                add(scenario.view(), BorderLayout.CENTER)
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
        val chrome: Boolean = true,
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
            // Inter and JetBrains Mono ship inside the JBR the tests run on (ideaIC/jbr/lib/fonts), so
            // CI and a dev machine rasterize the same glyphs; the logical "Dialog" font resolved to a
            // different system font on each and failed every golden on CI.
            val ideLabelFont = javax.swing.plaf.FontUIResource(GOLDEN_UI_FONT, java.awt.Font.PLAIN, 13)
            check(ideLabelFont.family == GOLDEN_UI_FONT) { "visual goldens need the JBR font $GOLDEN_UI_FONT, got ${ideLabelFont.family}" }
            val scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
            scheme.editorFontName = GOLDEN_MONO_FONT
            check(java.awt.Font(GOLDEN_MONO_FONT, java.awt.Font.PLAIN, 11).family == GOLDEN_MONO_FONT) {
                "visual goldens need the JBR font $GOLDEN_MONO_FONT"
            }
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

private const val GOLDEN_UI_FONT = "Inter"
private const val GOLDEN_MONO_FONT = "JetBrains Mono"

private object GoldenImages {
    private const val CHANNEL_TOLERANCE = 12
    // Same fonts everywhere (JBR Inter / JetBrains Mono), but each OS's freetype hints small text a
    // little differently: CI vs a dev machine measured 0.13–0.41 % of pixels (2026-10-02). A moved,
    // resized or recolored control changes several percent.
    private const val MAX_DIFFERENT_PIXEL_RATIO = 0.006

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

private fun settingTogglesFixture() = dev.acme.adbtoolbox.application.display.toggles.DeviceSettingTogglesViewState(
    toggles = dev.acme.adbtoolbox.application.display.toggles.DeviceSettingToggle.entries.associateWith { toggle ->
        when (toggle) {
            dev.acme.adbtoolbox.application.display.toggles.DeviceSettingToggle.MobileData ->
                QuickToggleFieldState.Error("Not supported on this device", null)
            dev.acme.adbtoolbox.application.display.toggles.DeviceSettingToggle.ShowLayoutBounds,
            dev.acme.adbtoolbox.application.display.toggles.DeviceSettingToggle.Wifi,
            -> QuickToggleFieldState.Idle(true)
            else -> QuickToggleFieldState.Idle(false)
        }
    },
    rotation = QuickToggleFieldState.Idle(dev.acme.adbtoolbox.domain.display.toggles.ScreenRotation.Landscape),
)

private fun developerOptionsFixture() = DeveloperOptionsViewState(
    toggles = mapOf(
        DeveloperToggle.StayAwake to QuickToggleFieldState.Idle(true),
        DeveloperToggle.DontKeepActivities to QuickToggleFieldState.Idle(false),
        DeveloperToggle.ShowViewUpdates to QuickToggleFieldState.Idle(false),
        DeveloperToggle.ShowSurfaceUpdates to QuickToggleFieldState.Error("Needs adb root", null),
    ),
    processLimit = QuickToggleFieldState.Idle(2),
)
