package io.github.dkej123.devicecockpit.e2e.tests

import com.intellij.remoterobot.fixtures.JListFixture
import com.intellij.remoterobot.search.locators.byXpath
import io.github.dkej123.devicecockpit.e2e.infra.Adb
import io.github.dkej123.devicecockpit.e2e.infra.E2eConfig
import io.github.dkej123.devicecockpit.e2e.infra.E2eTest
import io.github.dkej123.devicecockpit.e2e.infra.View
import io.github.dkej123.devicecockpit.e2e.infra.awaitUntil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** Device-backed coverage for installed-APK links and runtime permission mutations. */
class DeepLinksPermissionsE2ETest : E2eTest() {
    @BeforeEach
    fun openFixtureDetails() {
        Adb.shell("am force-stop $APP")
        Adb.shell("pm revoke --user 0 $APP $CAMERA")
        studio.navigate(View.Apps)
        if (APP !in rows()) {
            studio.click("Show system packages")
            Thread.sleep(500)
            studio.click("Show system packages")
        }
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$APP in the list") { APP in rows() }
        appsList().clickItemAtIndex(rows().indexOf(APP))
        studio.click("Details")
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "the details page") {
            studio.isShowing("//div[@class='AppDetailsPanel']")
        }
    }

    @AfterEach
    fun restoreFixture() {
        runCatching { studio.click("← Apps") }
        Adb.shell("pm revoke --user 0 $APP $CAMERA")
        Adb.shell("am force-stop $APP")
    }

    @Test
    fun `Analyze APK discovers the fixture and Open starts its activity`() {
        selectTab("Deep Links")
        named("appDetailsAnalyzeDeepLinks").click()
        val table = named("appDetailsDeepLinksTable")
        awaitUntil(E2eConfig.deviceTimeout(60), Duration.ofMillis(500), "an analyzed deep-link row") {
            table.callJs<Int>("component.getModel().getRowCount()", true) > 0
        }

        named("appDetailsDeepLinkUri").runJs("component.setText('$URI')", true)
        studio.clickWhenShowing { named("appDetailsOpenDeepLink") }

        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "$APP to be running") {
            Adb.pidOf(APP).isNotBlank()
        }
    }

    @Test
    fun `Grant changes a denied runtime permission and refreshes device state`() {
        selectTab("Permissions")
        val table = named("appDetailsPermissionsTable")
        val row = awaitPermissionRow(table)
        table.runJs("component.setRowSelectionInterval($row, $row)", true)
        studio.click("Grant")

        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "$CAMERA to be granted") {
            Adb.shell("dumpsys package $APP").lineSequence().any { line ->
                CAMERA in line && "granted=true" in line
            }
        }
    }

    private fun awaitPermissionRow(table: com.intellij.remoterobot.fixtures.ComponentFixture): Int {
        var row = -1
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$CAMERA in the permissions table") {
            row = table.callJs(
                "var m=component.getModel(); var r=-1; for(var i=0;i<m.getRowCount();i++) if(m.getValueAt(i,0)=='$CAMERA') r=i; r",
                true,
            )
            row >= 0
        }
        return row
    }

    private fun selectTab(title: String) {
        named("appDetailsTabs").runJs(
            "var i=component.indexOfTab('$title'); if(i<0) throw 'Missing tab: $title'; component.setSelectedIndex(i)",
            true,
        )
    }

    private fun rows(): List<String> = studio.listModelItems(appsList())
        .mapNotNull { Regex("""packageName=([\w.]+)""").find(it)?.groupValues?.get(1) }

    private fun appsList(): JListFixture = studio.toolWindow().find(
        JListFixture::class.java,
        byXpath("//div[@class='AppsVirtualList']"),
        Duration.ofSeconds(10),
    )

    private fun named(name: String) = studio.component("//div[@name='$name']")

    private companion object {
        const val APP = "dev.acme.adbtoolbox.e2efixture"
        const val CAMERA = "android.permission.CAMERA"
        const val URI = "toolbox://e2e/open/from-test"
    }
}
