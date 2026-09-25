package dev.acme.adbtoolbox.e2e.infra

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.fixtures.JListFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

/** The plugin's rail destinations, in rail order, with the panel each one shows. */
enum class View(val railIndex: Int, val panelClass: String?) {
    Device(0, "DeviceFactsPanel"),
    Apps(1, "AppsPanel"),
    Display(2, "DisplayPanel"),
    Network(3, "NetworkPanel"),
    Logcat(4, "LogcatPanel"),

    /** Opens the IDE Settings dialog instead of a panel. */
    Settings(5, null),
}

/**
 * Drives Android Studio like a user: finds components by their accessible name (the plugin's
 * accessibility pass gives every control one), clicks through the real AWT event queue, reads
 * what is rendered. Only setup/teardown uses in-IDE scripts (e.g. showing the tool window).
 */
class Studio(val robot: RemoteRobot = RemoteRobot(E2eConfig.robotUrl)) {

    private val ui = Duration.ofSeconds(15)

    // --- tool window and navigation -----------------------------------------------------------

    fun openToolWindow(): ContainerFixture {
        robot.runJs(
            PROJECT + """
            var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("ADB Toolbox");
            tw.show(null);
            """.trimIndent(),
            true,
        )
        return toolWindow()
    }

    /** Like a user switching to the editor/Project view and back (e.g. after pressing Run). */
    fun leaveAndReturnToToolWindow() {
        robot.runJs(
            PROJECT + """
            var manager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
            manager.getToolWindow("Project").activate(null);
            """.trimIndent(),
            true,
        )
        Thread.sleep(500)
        robot.runJs(
            PROJECT + "com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(\"ADB Toolbox\").activate(null);",
            true,
        )
    }

    fun toolWindow(): ContainerFixture =
        robot.find(ContainerFixture::class.java, byXpath("//div[@class='AdbToolboxToolWindowPanel']"), Duration.ofSeconds(30))

    fun rail(): JListFixture =
        toolWindow().find(JListFixture::class.java, byXpath("//div[contains(@javaclass,'NavigationRailPanel\$list')]"), ui)

    fun navigate(view: View) {
        val panel = view.panelClass
        if (panel == null) {
            // Settings is painted pinned to the bottom of the rail (the list maps clicks there
            // itself), so click where the user sees it rather than at its model cell bounds.
            val rail = rail()
            val (width, height) = rail.callJs<String>("component.getWidth() + 'x' + component.getHeight()", true)
                .split('x').map { it.toDouble().toInt() }
            rail.click(java.awt.Point(width / 2, height - 12))
            return
        }
        // A click right after a dialog/popup closes can be swallowed by window activation: retry.
        repeat(3) { attempt ->
            if (isShowing("//div[@class='$panel']")) return
            rail().clickItemAtIndex(view.railIndex)
            val shown = runCatching {
                awaitUntil(Duration.ofSeconds(5), Duration.ofMillis(200), "the $view view to show") { isShowing("//div[@class='$panel']") }
            }.isSuccess
            if (shown) return
            if (attempt == 2) error("the $view view did not show after 3 clicks on the rail")
        }
    }

    // --- finding and reading components ---------------------------------------------------------

    fun xpathByName(name: String, cls: String? = null): String =
        "//div[@accessiblename=${quote(name)}" + (cls?.let { " and @class='$it'" } ?: "") + "]"

    fun component(xpath: String, timeout: Duration = ui): ComponentFixture =
        toolWindow().find(ComponentFixture::class.java, byXpath(xpath), timeout)

    fun byName(name: String, cls: String? = null, timeout: Duration = ui): ComponentFixture =
        component(xpathByName(name, cls), timeout)

    /** Clicks like a user would: only once the control is enabled (controls stay disabled while
     * the plugin writes and re-reads device state, which takes seconds on a slow emulator). */
    fun click(name: String, cls: String? = null) {
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(300), "'$name' to be enabled") { isEnabled(byName(name, cls)) }
        clickWhenShowing { byName(name, cls) }
    }

    /** Clicks the component [find] returns, re-finding it if it was re-laid out mid-click. */
    fun clickWhenShowing(find: () -> ComponentFixture) {
        var last: Throwable? = null
        repeat(3) {
            try {
                find().click()
                return
            } catch (failure: Exception) {
                if (failure.message?.contains("must be showing on the screen") != true) throw failure
                last = failure
                Thread.sleep(500)
            }
        }
        throw last!!
    }

    fun isShowing(xpath: String): Boolean =
        runCatching { toolWindow().findAll(ComponentFixture::class.java, byXpath(xpath)).any { it.isShowing } }.getOrDefault(false)

    fun textOf(fixture: ComponentFixture): String = fixture.callJs("component.getText() + ''", true)

    fun isEnabled(fixture: ComponentFixture): Boolean = fixture.callJs("component.isEnabled()", true)

    fun isSelected(fixture: ComponentFixture): Boolean = fixture.callJs("component.isSelected()", true)

    /** The value label rendered right after a caption label (Device facts, Display values …). */
    fun valueAfterCaption(caption: String): String =
        textOf(component("//div[@class='JBLabel' and @accessiblename=${quote(caption)}]/following-sibling::div[1]"))

    /** Every non-empty label/button text currently showing inside [root] (default: the tool window). */
    fun visibleTexts(root: ComponentFixture = toolWindow()): List<String> {
        val joined: String = root.callJs(
            """
            // `instanceof javax.swing.X` silently evaluates to false in a fixture's script scope.
            var labelClass = java.lang.Class.forName("javax.swing.JLabel");
            var buttonClass = java.lang.Class.forName("javax.swing.AbstractButton");
            var containerClass = java.lang.Class.forName("java.awt.Container");
            var textClass = java.lang.Class.forName("javax.swing.text.JTextComponent");
            var out = [];
            function walk(c) {
                if (!c.isShowing()) return;
                if (labelClass.isInstance(c) || buttonClass.isInstance(c) || textClass.isInstance(c)) {
                    var t = c.getText(); if (t != null && t.length() > 0) out.push(t);
                }
                if (containerClass.isInstance(c)) {
                    var children = c.getComponents();
                    for (var i = 0; i < children.length; i++) walk(children[i]);
                }
            }
            walk(component);
            out.join("\u0001")
            """.trimIndent(),
            true,
        )
        return joined.split('\u0001').filter { it.isNotEmpty() }.map { it.replace(HTML_TAG, "").trim() }
    }

    /** `toString()` of every element in a JList's model (rendered rows are not labels). */
    fun listModelItems(list: ComponentFixture): List<String> {
        val joined: String = list.callJs(
            "var m = component.getModel(); var out = []; for (var i = 0; i < m.getSize(); i++) out.push(m.getElementAt(i) + ''); out.join('\u0001')",
            true,
        )
        return joined.split('\u0001').filter { it.isNotEmpty() }
    }

    fun waitForText(text: String, timeout: Duration = ui, root: () -> ComponentFixture = ::toolWindow) {
        awaitUntil(timeout, Duration.ofMillis(300), "text '$text' in the tool window") {
            visibleTexts(root()).any { text in it }
        }
    }

    // --- text entry -----------------------------------------------------------------------------

    fun typeInto(fixture: ComponentFixture, text: String, clearFirst: Boolean = true) {
        fixture.click()
        robot.keyboard {
            if (clearFirst) selectAll()
            if (clearFirst) backspace()
            enterText(text)
        }
    }

    fun setTextOf(fixture: ComponentFixture, text: String) {
        fixture.runJs("component.setText(${quoteJs(text)})", true)
    }

    fun pressEscape() = robot.keyboard { escape() }

    fun pressEnter() = robot.keyboard { enter() }

    // --- dialogs --------------------------------------------------------------------------------

    fun dialogXpath(title: String? = null): String =
        if (title == null) "//div[@class='MyDialog']" else "//div[@class='MyDialog' and contains(@title, ${quote(title)})]"

    fun dialog(title: String? = null, timeout: Duration = ui): ContainerFixture =
        robot.find(ContainerFixture::class.java, byXpath(dialogXpath(title)), timeout)

    fun isDialogOpen(title: String? = null): Boolean =
        robot.findAll(ContainerFixture::class.java, byXpath(dialogXpath(title))).isNotEmpty()

    fun dialogButton(dialog: ContainerFixture, text: String): ComponentFixture =
        dialog.find(ComponentFixture::class.java, byXpath("//div[@class='JButton' and @text=${quote(text)}]"), ui)

    private fun popupOpen(): Boolean =
        robot.findAll(ComponentFixture::class.java, byXpath("//div[@class='HeavyWeightWindow' or @class='DevicePickerListPanel']"))
            .any { it.isShowing }

    /** Closes every open modal dialog and popup (Escape); used to recover between tests. */
    fun closeDialogs() {
        repeat(5) {
            if (!isDialogOpen() && !popupOpen()) return
            pressEscape()
            Thread.sleep(500)
        }
    }

    // --- IDE services -------------------------------------------------------------------------

    fun clipboardText(): String = robot.callJs(
        """
        var t = com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor);
        t == null ? "" : t + ""
        """.trimIndent(),
        true,
    )

    fun setClipboard(text: String) = robot.runJs(
        "com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection(${quoteJs(text)}))",
        true,
    )

    fun invokeAction(actionId: String) = robot.runJs(
        PROJECT + """
        var am = com.intellij.openapi.actionSystem.ActionManager.getInstance();
        var action = am.getAction("$actionId");
        var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project);
        am.tryToExecute(action, null, frame.getRootPane(), "e2e", true);
        """.trimIndent(),
        true,
    )

    fun hideToolWindow(id: String) = robot.runJs(
        PROJECT + """
        var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("$id");
        if (tw != null && tw.isVisible()) tw.hide(null);
        """.trimIndent(),
        true,
    )

    /** Clicks "Dismiss" on every toast the plugin is showing (they overlay the bottom controls). */
    fun dismissToasts() {
        repeat(5) {
            val buttons = toolWindow().findAll(ComponentFixture::class.java, byXpath("//div[@accessiblename='Dismiss']")).filter { it.isShowing }
            if (buttons.isEmpty()) return
            buttons.first().click()
            Thread.sleep(200)
        }
    }

    /** Expires the IDE's own notification balloons (e.g. "Agent Mode now available"): they cover the bottom of the plugin panel. */
    fun expireIdeNotifications() = robot.runJs(
        PROJECT + """
        var type = java.lang.Class.forName("com.intellij.notification.Notification");
        var manager = com.intellij.notification.NotificationsManager.getNotificationsManager();
        var scoped = manager.getNotificationsOfType(type, project);
        for (var i = 0; i < scoped.length; i++) scoped[i].expire();
        var global = manager.getNotificationsOfType(type, null);
        for (var i = 0; i < global.length; i++) global[i].expire();
        """.trimIndent(),
        true,
    )

    fun isToolWindowVisible(id: String): Boolean = robot.callJs(
        PROJECT + """
        var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("$id");
        tw != null && tw.isVisible()
        """.trimIndent(),
        true,
    )

    fun toolWindowContentNames(id: String): List<String> {
        val joined: String = robot.callJs(
            PROJECT + """
            var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("$id");
            var out = [];
            if (tw != null) { var cs = tw.getContentManager().getContents(); for (var i = 0; i < cs.length; i++) out.push(cs[i].getDisplayName() + ""); }
            out.join("\u0001")
            """.trimIndent(),
            true,
        )
        return joined.split('\u0001').filter { it.isNotEmpty() }
    }

    fun screenshot(file: File) {
        file.parentFile.mkdirs()
        ImageIO.write(robot.getScreenshot(), "png", file)
    }

    companion object {
        const val PROJECT = "var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];\n"
        private val HTML_TAG = Regex("<[^>]+>")

        /** XPath string literal for arbitrary text (handles embedded quotes). */
        fun quote(text: String): String = when {
            '\'' !in text -> "'$text'"
            '"' !in text -> "\"$text\""
            else -> "concat('" + text.replace("'", "', \"'\", '") + "')"
        }

        fun quoteJs(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
