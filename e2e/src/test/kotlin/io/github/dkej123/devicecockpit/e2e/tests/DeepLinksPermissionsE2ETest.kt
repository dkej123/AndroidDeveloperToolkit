package io.github.dkej123.devicecockpit.e2e.tests

import com.intellij.remoterobot.fixtures.JListFixture
import com.intellij.remoterobot.search.locators.byXpath
import io.github.dkej123.devicecockpit.e2e.infra.Adb
import io.github.dkej123.devicecockpit.e2e.infra.E2eConfig
import io.github.dkej123.devicecockpit.e2e.infra.E2eTest
import io.github.dkej123.devicecockpit.e2e.infra.View
import io.github.dkej123.devicecockpit.e2e.infra.awaitUntil
import io.kotest.matchers.shouldBe
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
    fun `Device deep link query fields stay synchronized and Clear works after an unhandled link`() {
        studio.navigate(View.Device)
        studio.click("Deep link")
        studio.typeInto(named("deviceDeepLinkUri"), "unhandleddevicecockpit://item#details")
        studio.click("+ Add parameter")
        studio.typeInto(named("deviceDeepLinkQueryKey:0"), "ref")
        studio.typeInto(named("deviceDeepLinkQueryValue:0"), "IDE & QA")
        named("deviceDeepLinkUri").callJs<String>("component.getText()", true) shouldBe
            "unhandleddevicecockpit://item?ref=IDE%20%26%20QA#details"
        studio.click("Open on device")
        awaitUntil(E2eConfig.deviceTimeout(25), Duration.ofMillis(200), "Open to recover after an unhandled URI") {
            named("deviceDeepLinkOpen").callJs<Boolean>("component.isEnabled()", true) &&
                studio.visibleTexts().any { "No app on this device handles this link" in it }
        }
        studio.click("Clear form")
        named("deviceDeepLinkUri").callJs<String>("component.getText()", true) shouldBe ""
        named("deviceDeepLinkOpen").callJs<Boolean>("component.isEnabled()", true) shouldBe true
        studio.click("Close")
    }

    @Test
    fun `Device deep link dialog opens an arbitrary URI without APK analysis`() {
        studio.navigate(View.Device)
        studio.click("Deep link")
        val uri = "toolbox://e2e/open/from-dialog?ref=ide&source=test"
        studio.typeInto(named("deviceDeepLinkUri"), uri)
        studio.click("Open on device")
        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "$APP launched from the device dialog") {
            Adb.pidOf(APP).isNotBlank()
        }
        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(200), "the successful link in the still-open dialog's history") {
            named("deviceDeepLinkHistory").callJs<Boolean>("component.getRowCount() > 0 && component.getValueAt(component.getRowCount() - 1, 1).equals('$uri')", true)
        }
        studio.click("Close")
    }

    @Test
    fun `Analyze APK discovers the fixture and Open starts its activity`() {
        selectTab("Deep Links")
        named("appDetailsAnalyzeDeepLinks").click()
        // DeepLinksList: one row per link, named after its template.
        awaitUntil(E2eConfig.deviceTimeout(60), Duration.ofMillis(500), "an analyzed deep-link row") {
            studio.robot.findAll(com.intellij.remoterobot.fixtures.ComponentFixture::class.java, byXpath("//div[starts-with(@name, 'appDetailsDeepLink:')]")).isNotEmpty()
        }

        // The list shows the last analysis while a new one runs; Open waits for it to finish.
        awaitUntil(E2eConfig.deviceTimeout(60), Duration.ofMillis(500), "the analysis to finish") {
            named("appDetailsOpenDeepLink").callJs<Boolean>("component.isEnabled()", true)
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
        val action = "appDetailsPermissionAction:$CAMERA"
        awaitUntil(E2eConfig.deviceTimeout(20), Duration.ofMillis(500), "$CAMERA in the permissions list") {
            studio.robot.findAll(com.intellij.remoterobot.fixtures.ComponentFixture::class.java, byXpath("//div[@name='$action']")).isNotEmpty()
        }
        named(action).callJs<String>("component.getText()", true) shouldBe "Grant"
        studio.clickWhenShowing { named(action) }

        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "$CAMERA to be granted") {
            Adb.shell("dumpsys package $APP").lineSequence().any { line ->
                CAMERA in line && "granted=true" in line
            }
        }
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
        const val APP = "dev.acme.devicecockpit.e2efixture"
        const val CAMERA = "android.permission.CAMERA"
        const val URI = "toolbox://e2e/open/from-test"
    }
}
