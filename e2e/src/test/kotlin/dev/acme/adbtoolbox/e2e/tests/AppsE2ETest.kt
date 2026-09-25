package dev.acme.adbtoolbox.e2e.tests

import com.intellij.remoterobot.fixtures.JListFixture
import com.intellij.remoterobot.search.locators.byXpath
import dev.acme.adbtoolbox.e2e.infra.Adb
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration

/**
 * Apps view against the fixture apps from e2e/fixtures/apks.txt: listing, filtering, selection and
 * every lifecycle action, each verified on the device.
 */
class AppsE2ETest : E2eTest() {

    @BeforeEach
    fun openAppsView() {
        studio.navigate(View.Apps)
        reinstallMissingFixtures()
        awaitPackages(FIXTURES)
    }

    @AfterEach
    fun resetFilter() {
        studio.setTextOf(studio.byName("Filter packages"), "")
        reinstallMissingFixtures()
    }

    @Test
    fun `lists every installed user app`() {
        packageRows() shouldContainAll FIXTURES
    }

    @Test
    fun `an app installed outside the plugin appears in the list`() {
        // The everyday flow: Run in Android Studio installs the app, then the user wants to
        // restart it or clear its data here. Nothing in the UI refreshes the list today.
        Adb.shell("pm uninstall $COFFEE")
        forceListRefresh()
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "$COFFEE to leave the list") { COFFEE !in packageRows() }

        Adb.install(File(E2eConfig.fixtures, "$COFFEE.apk"))
        studio.leaveAndReturnToToolWindow()

        awaitPackages(listOf(COFFEE), E2eConfig.deviceTimeout(15))
    }

    @Test
    fun `rows show the app label and launcher icon, not only the package name`() {
        // design/README.md §4: two-line row, "label" over "package". Labels and icons come from the
        // on-device app-info helper (ADR 0007) and arrive after the plain package list.
        awaitUntil(E2eConfig.deviceTimeout(60), Duration.ofMillis(500), "$MARKOR to get its label and icon") {
            val markorRow = studio.listModelItems(appsList()).singleOrNull { "packageName=$MARKOR" in it }
            markorRow != null && "label=Markor" in markorRow && "icon=AppIcon(" in markorRow
        }
    }

    @Test
    fun `filter narrows the list by package name`() {
        studio.typeInto(studio.byName("Filter packages"), "markor")

        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(300), "only markor to remain") {
            packageRows() == listOf(MARKOR)
        }
    }

    @Test
    fun `show system packages adds platform packages`() {
        studio.click("Show system packages")

        awaitPackages(listOf("com.android.settings"), E2eConfig.deviceTimeout(30))
        studio.click("Show system packages")
        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "system packages to disappear again") {
            "com.android.settings" !in packageRows()
        }
    }

    @Test
    fun `Launch starts the selected app`() {
        Adb.shell("am force-stop $COFFEE")
        select(COFFEE)

        studio.click("Launch")

        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$COFFEE to run") { Adb.pidOf(COFFEE).isNotEmpty() }
    }

    @Test
    fun `Force-stop kills the selected app`() {
        Adb.shell("monkey -p $COFFEE -c android.intent.category.LAUNCHER 1")
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$COFFEE to run") { Adb.pidOf(COFFEE).isNotEmpty() }
        select(COFFEE)

        studio.click("Force-stop")

        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$COFFEE to stop") { Adb.pidOf(COFFEE).isEmpty() }
    }

    @Test
    fun `Restart relaunches the selected app in a new process`() {
        Adb.shell("monkey -p $COFFEE -c android.intent.category.LAUNCHER 1")
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$COFFEE to run") { Adb.pidOf(COFFEE).isNotEmpty() }
        val oldPid = Adb.pidOf(COFFEE)
        select(COFFEE)

        studio.click("Restart")

        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "$COFFEE to run in a new process") {
            Adb.pidOf(COFFEE).let { it.isNotEmpty() && it != oldPid }
        }
    }

    @Test
    fun `Clear data asks for confirmation, Cancel keeps the data`() {
        val marker = plantDataMarker(EDITOR)
        select(EDITOR)

        studio.click("Clear data")
        val dialog = studio.dialog("Clear data for $EDITOR")
        studio.dialogButton(dialog, "Cancel").click()

        Thread.sleep(E2eConfig.deviceTimeout(2).toMillis())
        Adb.shell("ls $marker") shouldBe marker
    }

    @Test
    fun `Clear data after confirmation wipes the app's data`() {
        val marker = plantDataMarker(EDITOR)
        select(EDITOR)

        studio.click("Clear data")
        studio.dialogButton(studio.dialog("Clear data for $EDITOR"), "Clear data").click()

        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "app data to be wiped") {
            Adb.shell("ls $marker 2>/dev/null").isEmpty()
        }
        Adb.isInstalled(EDITOR) shouldBe true
    }

    @Test
    fun `Uninstall after confirmation removes the app and its row`() {
        select(EDITOR)

        studio.click("Uninstall")
        studio.dialogButton(studio.dialog("Uninstall $EDITOR"), "Uninstall").click()

        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "$EDITOR to be uninstalled") { !Adb.isInstalled(EDITOR) }
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$EDITOR row to disappear") { EDITOR !in packageRows() }
        packageRows() shouldNotContain EDITOR
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun appsList(): JListFixture =
        studio.toolWindow().find(JListFixture::class.java, byXpath("//div[@class='AppsVirtualList']"), Duration.ofSeconds(10))

    /** Package names of the rows currently in the list model. */
    private fun packageRows(): List<String> = studio.listModelItems(appsList()).mapNotNull { row ->
        PACKAGE.find(row)?.groupValues?.get(1)
    }

    private fun awaitPackages(expected: List<String>, timeout: Duration = E2eConfig.deviceTimeout(20)) {
        awaitUntil(timeout, Duration.ofMillis(500), "packages $expected in the Apps list") { packageRows().containsAll(expected) }
    }

    private fun select(packageName: String) {
        // Rows re-sort once app labels arrive, so a looked-up index can go stale before the click
        // lands: click until the model reports this exact package as selected.
        awaitUntil(Duration.ofSeconds(20), Duration.ofMillis(500), "$packageName to be selected") {
            val index = packageRows().indexOf(packageName)
            check(index >= 0) { "$packageName not in ${packageRows()}" }
            appsList().clickItemAtIndex(index)
            studio.listModelItems(appsList()).any { "packageName=$packageName," in it && "isSelected=true" in it }
        }
        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(200), "Launch to become enabled") { studio.isEnabled(studio.byName("Launch")) }
    }

    /** Creates a file in the app's private data directory (root adbd, see start-emulator.sh). */
    private fun plantDataMarker(packageName: String): String {
        val dir = "/data/data/$packageName/files"
        val marker = "$dir/e2e-marker"
        Adb.shell("mkdir -p $dir && touch $marker")
        check(Adb.shell("ls $marker") == marker) { "could not create $marker (is adbd running as root?)" }
        return marker
    }

    private fun reinstallMissingFixtures() {
        FIXTURES.filterNot(Adb::isInstalled).forEach { Adb.install(File(E2eConfig.fixtures, "$it.apk")) }
        if (studio.isShowing("//div[@class='AppsPanel']") && !packageRows().containsAll(FIXTURES)) forceListRefresh()
    }

    /**
     * Workaround while the Apps view has no refresh control (see the test above): changing the
     * package scope re-queries the device.
     */
    private fun forceListRefresh() {
        studio.click("Show system packages")
        Thread.sleep(500)
        studio.click("Show system packages")
    }

    private companion object {
        const val EDITOR = "org.billthefarmer.editor"
        const val MARKOR = "net.gsantner.markor"
        const val COFFEE = "com.github.muellerma.coffee"
        val FIXTURES = listOf(EDITOR, MARKOR, COFFEE)
        val PACKAGE = Regex("""packageName=([\w.]+)""")
    }
}
