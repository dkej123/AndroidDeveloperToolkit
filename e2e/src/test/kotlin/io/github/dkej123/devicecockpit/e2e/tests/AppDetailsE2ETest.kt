package io.github.dkej123.devicecockpit.e2e.tests

import com.intellij.remoterobot.fixtures.JListFixture
import com.intellij.remoterobot.search.locators.byXpath
import io.github.dkej123.devicecockpit.e2e.infra.Adb
import io.github.dkej123.devicecockpit.e2e.infra.E2eConfig
import io.github.dkej123.devicecockpit.e2e.infra.E2eTest
import io.github.dkej123.devicecockpit.e2e.infra.View
import io.github.dkej123.devicecockpit.e2e.infra.awaitUntil
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * App details: info, and editing an app's shared preferences and database on the device. The
 * fixture app is not debuggable, so this exercises the root-shell path (`adb root` on the test
 * emulator); run-as is covered by unit tests. The files are planted with plain adb first.
 */
class AppDetailsE2ETest : E2eTest() {

    private val dataDir = "/data/data/$APP"

    @BeforeEach
    fun plantFilesAndOpenDetails() {
        val uid = Adb.shell("stat -c %u $dataDir")
        Adb.shell("mkdir -p $dataDir/shared_prefs $dataDir/databases")
        Adb.shell("echo '<?xml version=\"1.0\" ?><map><string name=\"greeting\">hi</string><int name=\"count\" value=\"1\" /></map>' > $dataDir/shared_prefs/e2e.xml")
        Adb.shell("rm -f $dataDir/databases/e2e.db*; sqlite3 $dataDir/databases/e2e.db \"CREATE TABLE notes(id INTEGER PRIMARY KEY, body TEXT); INSERT INTO notes(body) VALUES('first');\"")
        Adb.shell("chown -R $uid:$uid $dataDir/shared_prefs $dataDir/databases && restorecon -R $dataDir")

        studio.navigate(View.Apps)
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$APP in the list") { APP in rows() }
        val index = rows().indexOf(APP)
        appsList().clickItemAtIndex(index)
        studio.click("Details")
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "the details page") {
            studio.isShowing("//div[@class='AppDetailsPanel']")
        }
    }

    @AfterEach
    fun backToList() {
        runCatching { studio.click("← Apps") }
        Adb.shell("rm -f $dataDir/shared_prefs/e2e.xml $dataDir/databases/e2e.db*")
    }

    @Test
    fun `the info tab shows the version and that files are reachable`() {
        selectTab("Info")
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "version and file access") {
            studio.visibleTexts().let { texts -> texts.any { it == "root shell" } && texts.any { it.startsWith("v") || it.contains("·") } }
        }
    }

    @Test
    fun `a shared preference edited in the table is written to the device`() {
        selectTab("Shared prefs")
        chooseInCombo("appDetailsPrefsFiles", "e2e.xml")
        val table = named("appDetailsPrefsTable")
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "the e2e.xml entries") {
            table.callJs<Int>("component.getModel().getRowCount()", true) == 2
        }
        val row = table.callJs<Int>("var m = component.getModel(); var r = -1; for (var i = 0; i < m.getRowCount(); i++) if (m.getValueAt(i, 0) == 'greeting') r = i; r", true)
        table.runJs("component.getModel().setValueAt('hello there', $row, 2)", true)

        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(300), "Save to be enabled") { studio.isEnabled(named("appDetailsSavePrefs")) }
        studio.clickWhenShowing { named("appDetailsSavePrefs") }

        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "the new value on the device") {
            Adb.shell("cat $dataDir/shared_prefs/e2e.xml").contains("<string name=\"greeting\">hello there</string>")
        }
        Adb.shell("cat $dataDir/shared_prefs/e2e.xml") shouldContain "<int name=\"count\" value=\"1\" />"
        // Written in place, so the app still owns its file.
        Adb.shell("stat -c %u $dataDir/shared_prefs/e2e.xml") shouldContain Adb.shell("stat -c %u $dataDir")
    }

    @Test
    fun `a database cell edited in the table is written to the device`() {
        selectTab("Databases")
        chooseInCombo("appDetailsDatabaseFiles", "e2e.db")
        val table = named("appDetailsRowsTable")
        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "the notes rows") {
            runCatching { table.callJs<String>("component.getModel().getRowCount() + ':' + component.getModel().getColumnCount()", true) }.getOrNull() == "1:2"
        }
        table.runJs("component.getModel().setValueAt('edited', 0, 1)", true)

        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(300), "Save to be enabled") { studio.isEnabled(named("appDetailsSaveDatabase")) }
        studio.clickWhenShowing { named("appDetailsSaveDatabase") }

        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "the edited row on the device") {
            Adb.shell("sqlite3 $dataDir/databases/e2e.db 'SELECT body FROM notes'") == "edited"
        }
    }

    private fun rows(): List<String> = studio.listModelItems(appsList()).mapNotNull { Regex("""packageName=([\w.]+)""").find(it)?.groupValues?.get(1) }

    private fun appsList(): JListFixture =
        studio.toolWindow().find(JListFixture::class.java, byXpath("//div[@class='AppsVirtualList']"), Duration.ofSeconds(10))

    // Scaled: on a software-emulated device the App details tabs load in well over 15 s.
    private fun named(name: String) = studio.component("//div[@name='$name']", E2eConfig.deviceTimeout(20))

    private fun selectTab(title: String) {
        named("appDetailsTabs").runJs(
            "var i = component.indexOfTab(${io.github.dkej123.devicecockpit.e2e.infra.Studio.quoteJs(title)}); if (i < 0) throw 'Missing tab: $title'; component.setSelectedIndex(i)",
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

    private companion object {
        const val APP = "net.gsantner.markor"
    }
}
