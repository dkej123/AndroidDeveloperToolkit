package dev.acme.adbtoolbox.e2e.tests

import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.search.locators.byXpath
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.Studio
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration

/** The plugin's Settings page (Settings › Tools › ADB Toolbox) and its effect on the tool window. */
class SettingsE2ETest : E2eTest() {

    private var captureDir: File? = null

    @AfterEach
    fun restoreDefaults() {
        studio.closeDialogs()
        openPluginSettings().let { dialog ->
            field(dialog, "captureDirectoryField").text = ""
            field(dialog, "adbPathField").text = ""
            studio.dialogButton(dialog, "OK").click()
        }
        captureDir?.deleteRecursively()
    }

    @Test
    fun `the rail's Settings item opens the ADB Toolbox settings page`() {
        studio.navigate(View.Settings)

        val dialog = studio.dialog("Settings")
        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(300), "the ADB Toolbox page") {
            dialog.findAll(ContainerFixture::class.java, byXpath("//div[@name='captureDirectoryField']")).any { it.isShowing }
        }
        studio.closeDialogs()
    }

    @Test
    fun `capture directory setting is shown in the Device view and used for screenshots`() {
        val dir = Files.createTempDirectory("e2e-capture").toFile().also { captureDir = it }
        val dialog = openPluginSettings()
        field(dialog, "captureDirectoryField").text = dir.path
        studio.dialogButton(dialog, "OK").click()

        studio.navigate(View.Device)
        studio.visibleTexts() shouldContain dir.path.replace(System.getProperty("user.home"), "~")
        studio.click("Screenshot")

        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "a PNG in $dir") {
            dir.listFiles().orEmpty().any { it.name.endsWith(".png") && it.length() > 0 }
        }
    }

    @Test
    fun `an adb path that is not an executable is rejected`() {
        val dialog = openPluginSettings()
        studio.typeInto(field(dialog, "adbPathField"), "/definitely/not/adb")
        val apply = studio.dialogButton(dialog, "Apply")
        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(300), "Apply to be enabled") { studio.isEnabled(apply) }

        apply.click()

        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(300), "a validation error") {
            studio.visibleTexts(dialog).any { "must point to an executable file" in it }
        }
        field(dialog, "adbPathField").text = ""
        studio.dialogButton(dialog, "Cancel").click()
    }

    @Test
    fun `settings survive reopening the page`() {
        val dir = Files.createTempDirectory("e2e-capture").toFile().also { captureDir = it }
        openPluginSettings().let { dialog ->
            field(dialog, "captureDirectoryField").text = dir.path
            studio.dialogButton(dialog, "OK").click()
        }

        val reopened = openPluginSettings()

        field(reopened, "captureDirectoryField").text shouldBe dir.path
        studio.dialogButton(reopened, "Cancel").click()
    }

    /** Opens Settings on the plugin's page directly (independent of the rail item under test). */
    private fun openPluginSettings(): ContainerFixture {
        studio.robot.runJs(
            Studio.PROJECT + """
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(function() {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "ADB Toolbox");
            });
            """.trimIndent(),
            false,
        )
        val dialog = studio.dialog("Settings")
        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(300), "the ADB Toolbox page") {
            dialog.findAll(ContainerFixture::class.java, byXpath("//div[@name='captureDirectoryField']")).any { it.isShowing }
        }
        return dialog
    }

    private fun field(dialog: ContainerFixture, name: String): JTextFieldFixture =
        dialog.find(JTextFieldFixture::class.java, byXpath("//div[@name='$name']"), Duration.ofSeconds(5))
}
