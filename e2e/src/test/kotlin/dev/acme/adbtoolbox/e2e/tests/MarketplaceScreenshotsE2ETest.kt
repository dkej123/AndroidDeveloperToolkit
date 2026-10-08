package dev.acme.adbtoolbox.e2e.tests

import com.intellij.remoterobot.fixtures.JListFixture
import com.intellij.remoterobot.search.locators.byXpath
import dev.acme.adbtoolbox.e2e.infra.Adb
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.Studio
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

/**
 * JetBrains Marketplace screenshots (docs/marketplace.md), taken from the real Android Studio and
 * emulator. Not a regression test: tagged `marketplace` and excluded from the default run; started
 * by `release/screenshots.sh`. Every picture is the whole IDE window: a Kotlin file in the editor on
 * the left, the full ADB Toolbox tool window on the right — one per view and per App details tab,
 * saved as `<nn>-<scene>-<theme>.png` into `E2E_SCREENSHOT_DIR`. All in Islands Dark (Studio's
 * default): switching to Islands Light at runtime leaves the editor island dark until a restart.
 */
@Tag("marketplace")
class MarketplaceScreenshotsE2ETest : E2eTest() {

    private val outputDir = File(System.getenv("E2E_SCREENSHOT_DIR") ?: File(E2eConfig.reportDir, "marketplace").path)
    private var originalTheme: String = ""
    private var originalBounds: String = ""

    @BeforeEach
    fun prepare() {
        // Device defaults, so no override from an earlier run shows up as amber in the pictures.
        Adb.putSetting("system", "font_scale", "1.0")
        Adb.shell("wm density reset")
        Adb.shell("cmd uimode night no")
        plantAppData()
        outputDir.mkdirs()
        originalTheme = studio.robot.callJs("com.intellij.ide.ui.LafManager.getInstance().getCurrentUIThemeLookAndFeel().getName() + ''", true)
        originalBounds = studio.robot.callJs("$FRAME var b = frame.getBounds(); b.x + ',' + b.y + ',' + b.width + ',' + b.height", true)
        studio.robot.runJs("$FRAME frame.setExtendedState(java.awt.Frame.NORMAL); frame.setBounds(0, 0, $WIDTH, $HEIGHT);", true)
        openSampleCode()
        studio.hideToolWindow("Project")
        studio.openToolWindow()
        warmUp()
    }

    /**
     * A freshly started IDE and emulator are busiest right at the start: reads fired then may time
     * out and leave their error (and the status line) on screen. Let it settle, enter the Device view
     * again so everything is re-read, and replace a stale status line with a fresh one.
     */
    private fun warmUp() {
        Thread.sleep(WARM_UP_MS)
        studio.closeDialogs()
        studio.expireIdeNotifications()
        studio.navigate(View.Network)
        studio.navigate(View.Device)
        Thread.sleep(WARM_UP_MS)
        if (studio.visibleTexts().any { "timed out" in it }) {
            studio.click("Copy report")
            Thread.sleep(SETTLE_MS)
        }
    }

    @AfterEach
    fun restore() {
        runCatching { setTheme(originalTheme) }
        runCatching {
            val (x, y, w, h) = originalBounds.split(',').map { it.trim().toDouble().toInt() }
            studio.robot.runJs("$FRAME frame.setBounds($x, $y, $w, $h);", true)
        }
        runCatching { studio.click("← Apps") }
        runCatching { Adb.shell("rm -f $DATA_DIR/shared_prefs/settings.xml $DATA_DIR/databases/notes.db*") }
    }

    @Test
    fun `capture the Marketplace screenshot set`() {
        scene(1, "device", DARK) {
            // Current app (first section) shows a real app with its facts, not the home screen.
            Adb.shell("monkey -p $LOGCAT_APP -c android.intent.category.LAUNCHER 1")
            studio.navigate(View.Device)
            awaitUntil(E2eConfig.deviceTimeout(60), Duration.ofMillis(500), "the current app facts") {
                studio.visibleTexts().let { texts -> texts.any { it.startsWith("updated") } && "reading…" !in texts }
            }
            // Every quick toggle and the process limit read back (they show "—" while reading).
            awaitUntil(E2eConfig.deviceTimeout(60), Duration.ofMillis(500), "the quick toggles") {
                studio.visibleTexts().let { texts -> texts.any { Regex("""\d+ of \d+ on""").matches(it) } && "standard" in texts }
            }
            scrollDeviceViewToTop()
        }
        scene(2, "apps", DARK) { selectSampleApp() }
        scene(3, "app-info", DARK) {
            openDetails("Info")
            // The fixture has no versionName; "root shell" under FILE ACCESS means the info loaded.
            awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "the app info") {
                studio.visibleTexts().any { it == "root shell" }
            }
        }
        scene(4, "app-deep-links", DARK) {
            openDetails("Deep Links")
            named("appDetailsAnalyzeDeepLinks").click()
            awaitUntil(E2eConfig.deviceTimeout(60), Duration.ofMillis(500), "the deep-link table") {
                named("appDetailsDeepLinksTable").callJs<Boolean>("component.getRowCount() > 0", true)
            }
            named("appDetailsDeepLinkUri").runJs("component.setText('toolbox://e2e/open/welcome')", true)
        }
        scene(5, "app-permissions", DARK) {
            openDetails("Permissions")
            awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "the permissions table") {
                named("appDetailsPermissionsTable").callJs<Boolean>("component.getRowCount() > 0", true)
            }
        }
        scene(6, "app-shared-prefs", DARK) {
            openDetails("Shared prefs")
            chooseInCombo("appDetailsPrefsFiles", "settings.xml")
        }
        scene(7, "app-databases", DARK) {
            openDetails("Databases")
            chooseInCombo("appDetailsDatabaseFiles", "notes.db")
        }
        runCatching { studio.click("← Apps") }
        scene(8, "logcat", DARK) {
            Adb.shell("monkey -p $LOGCAT_APP -c android.intent.category.LAUNCHER 1")
            studio.navigate(View.Logcat)
            Thread.sleep(3_000)
        }
        scene(9, "network", DARK) {
            studio.navigate(View.Network)
            // Filled in, not applied: the picture shows the form without changing the device proxy.
            studio.setTextOf(studio.byName("Proxy host"), "10.0.2.2")
            studio.setTextOf(studio.byName("Proxy port"), "8888")
        }
    }

    /** The Device view keeps its scroll position across navigation; the picture starts at Current app. */
    private fun scrollDeviceViewToTop() {
        studio.component("//div[@class='DeviceFactsPanel']").runJs(
            """
            var scroll = java.lang.Class.forName("javax.swing.JScrollPane");
            var found = null;
            function walk(c) {
                if (found == null && scroll.isInstance(c) && c.isShowing()) found = c;
                var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) walk(ch[i]);
            }
            walk(component);
            if (found != null) found.getViewport().setViewPosition(new java.awt.Point(0, 0));
            """.trimIndent(),
            true,
        )
    }

    private fun scene(index: Int, name: String, theme: String, arrange: () -> Unit) {
        setTheme(theme)
        // A store picture must show the plugin working: no loading placeholders, no timeouts, no
        // IDE balloons. A slow device gets a few more tries; then the run fails instead of shipping
        // a broken image (docs/marketplace.md).
        var problems: List<String> = emptyList()
        repeat(ATTEMPTS) { attempt ->
            studio.expireIdeNotifications()
            studio.openToolWindow()
            widenToolWindow()
            studio.dismissToasts()
            if (attempt > 0) {
                // The status line keeps the last message: replace a stale timeout with a fresh one.
                if (problems.any { "timed out" in it }) {
                    studio.navigate(View.Device)
                    runCatching { studio.click("Copy report") }
                    Thread.sleep(SETTLE_MS)
                }
                // A retry enters the view again, which re-reads what failed the last time.
                studio.navigate(if (name == "network") View.Device else View.Network)
            }
            arrange()
            Thread.sleep(SETTLE_MS * (attempt + 1))
            studio.dismissToasts()
            studio.expireIdeNotifications()
            problems = studio.visibleTexts().filter { text -> BAD_STATE.any { it in text } }
            if (problems.isEmpty()) {
                val image = studio.robot.getScreenshot().getSubimage(0, 0, WIDTH, HEIGHT)
                ImageIO.write(image, "png", File(outputDir, "%02d-%s-%s.png".format(index, name, if (theme == DARK) "dark" else "light")))
                return
            }
        }
        error("scene $name still shows $problems after $ATTEMPTS attempts; the device is too slow for store screenshots")
    }

    private fun setTheme(name: String) {
        studio.robot.runJs(
            """
            var laf = com.intellij.ide.ui.LafManager.getInstance();
            var it = laf.getInstalledThemes().iterator();
            while (it.hasNext()) {
                var t = it.next();
                if (t.getName() == "$name") {
                    // Rhino needs the overload spelled out: setCurrentLookAndFeel is overloaded.
                    laf["setCurrentLookAndFeel(com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo,boolean)"](t, false);
                    // The editor keeps its own color scheme: switch it to the theme's one too.
                    // Scheme names can carry the "_@user_" prefix of an editable copy (Studio 2026.1).
                    var schemes = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance();
                    var wanted = t.getEditorSchemeId() != null ? t.getEditorSchemeId() : ("$name".indexOf("Dark") >= 0 ? "Dark" : "Light");
                    var all = schemes.getAllSchemes();
                    for (var i = 0; i < all.length; i++) {
                        var n = all[i].getName() + "";
                        if (n == wanted || n == "_@user_" + wanted) { schemes.setGlobalScheme(all[i]); break; }
                    }
                    laf.updateUI();
                    var windows = java.awt.Window.getWindows();
                    for (var w = 0; w < windows.length; w++) windows[w].repaint();
                    break;
                }
            }
            """.trimIndent(),
            true,
        )
        Thread.sleep(SETTLE_MS)
    }

    /** Wide enough for the device facts grid, three-column toggle tiles and the App details tabs. */
    private fun widenToolWindow() = studio.robot.runJs(
        Studio.PROJECT + """
        var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("ADB Toolbox");
        var c = tw.getComponent();
        tw.stretchWidth($TOOL_WINDOW_WIDTH - c.getWidth());
        """.trimIndent(),
        true,
    )

    /** A small Compose screen in the editor, so every picture shows the plugin next to real code. */
    private fun openSampleCode() {
        val file = File(System.getenv("E2E_PROJECT_DIR") ?: File(E2eConfig.home, "test-project").path, "src/CheckoutScreen.kt")
        file.parentFile.mkdirs()
        file.writeText(SAMPLE_CODE)
        // VFS refresh and opening an editor need write-intent access: hand it to the IDE's own queue.
        studio.robot.runJs(
            Studio.PROJECT + """
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(function() {
                var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("${file.absolutePath}");
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true);
            });
            """.trimIndent(),
            false,
        )
        Thread.sleep(SETTLE_MS)
    }

    private fun plantAppData() {
        val uid = Adb.shell("stat -c %u $DATA_DIR")
        Adb.shell("mkdir -p $DATA_DIR/shared_prefs $DATA_DIR/databases")
        Adb.shell(
            "echo '<?xml version=\"1.0\" ?><map><boolean name=\"onboarding_done\" value=\"true\" />" +
                "<string name=\"theme\">system</string><int name=\"launch_count\" value=\"42\" />" +
                "<string name=\"api_base_url\">https://api.example.com</string></map>' > $DATA_DIR/shared_prefs/settings.xml",
        )
        Adb.shell(
            "rm -f $DATA_DIR/databases/notes.db*; sqlite3 $DATA_DIR/databases/notes.db " +
                "\"CREATE TABLE notes(id INTEGER PRIMARY KEY, title TEXT, pinned INTEGER, updated_at TEXT); " +
                "INSERT INTO notes(title, pinned, updated_at) VALUES('Release checklist', 1, '2026-10-01'), " +
                "('Deep link QA', 0, '2026-09-30'), ('Proxy setup', 0, '2026-09-28');\"",
        )
        Adb.shell("chown -R $uid:$uid $DATA_DIR/shared_prefs $DATA_DIR/databases && restorecon -R $DATA_DIR")
    }

    private fun selectSampleApp() {
        studio.navigate(View.Apps)
        if (studio.isShowing("//div[@class='AppDetailsPanel']")) studio.click("← Apps")
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$SAMPLE_APP in the list") { SAMPLE_APP in appRows() }
        appsList().clickItemAtIndex(appRows().indexOf(SAMPLE_APP))
    }

    private fun openDetails(tab: String) {
        // The Apps view remembers the open details page, also after visiting another view.
        studio.navigate(View.Apps)
        if (!studio.isShowing("//div[@class='AppDetailsPanel']")) {
            selectSampleApp()
            studio.click("Details")
            awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "the details page") {
                studio.isShowing("//div[@class='AppDetailsPanel']")
            }
        }
        named("appDetailsTabs").runJs(
            "var i = component.indexOfTab(${Studio.quoteJs(tab)}); if (i < 0) throw 'Missing tab: $tab'; component.setSelectedIndex(i)",
            true,
        )
    }

    private fun chooseInCombo(name: String, item: String) {
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$item in $name") {
            named(name).callJs<Boolean>(
                "var found = false; for (var i = 0; i < component.getItemCount(); i++) if (component.getItemAt(i) == '$item') found = true; found",
                true,
            )
        }
        named(name).runJs("component.setSelectedItem('$item')", true)
    }

    private fun named(name: String) = studio.component("//div[@name='$name']")

    private fun appRows(): List<String> =
        studio.listModelItems(appsList()).mapNotNull { Regex("""packageName=([\w.]+)""").find(it)?.groupValues?.get(1) }

    private fun appsList(): JListFixture =
        studio.toolWindow().find(JListFixture::class.java, byXpath("//div[@class='AppsVirtualList']"), Duration.ofSeconds(15))

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val TOOL_WINDOW_WIDTH = 680
        const val SETTLE_MS = 2_000L
        const val WARM_UP_MS = 15_000L
        const val ATTEMPTS = 8
        val BAD_STATE = listOf("Loading", "timed out", "Timed out", "Querying", "Reading installed APK")

        /** The E2E fixture app: exported deep links, runtime permissions, debuggable data dir. */
        const val SAMPLE_APP = "dev.acme.adbtoolbox.e2efixture"
        const val DATA_DIR = "/data/data/$SAMPLE_APP"

        /** An app with a launcher activity, started so Logcat has something real to show. */
        const val LOGCAT_APP = "net.gsantner.markor"
        const val DARK = "Islands Dark"
        const val LIGHT = "Islands Light"
        const val FRAME = Studio.PROJECT + "var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project);"

        val SAMPLE_CODE = """
            package com.example.shop.checkout

            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Button
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.collectAsState
            import androidx.compose.runtime.getValue
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp

            /** Checkout screen: shows the cart total and places the order. */
            @Composable
            fun CheckoutScreen(viewModel: CheckoutViewModel, onDone: () -> Unit) {
                val state by viewModel.state.collectAsState()

                Column(Modifier.padding(16.dp)) {
                    Text("Items: ${'$'}{state.items.size}")
                    Text("Total: ${'$'}{state.total.format()}")

                    if (state.error != null) {
                        Text("Something went wrong: ${'$'}{state.error}")
                    }

                    Button(
                        onClick = { viewModel.placeOrder(onSuccess = onDone) },
                        enabled = !state.placingOrder && state.items.isNotEmpty(),
                    ) {
                        Text(if (state.placingOrder) "Placing order…" else "Place order")
                    }
                }
            }

            data class CheckoutState(
                val items: List<CartItem> = emptyList(),
                val total: Money = Money.ZERO,
                val placingOrder: Boolean = false,
                val error: String? = null,
            )
        """.trimIndent() + "\n"
    }
}
