package dev.acme.adbtoolbox.e2e.infra

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import java.io.File
import java.net.URI
import java.time.Duration

/**
 * Base for every E2E test: checks the environment is up, brings the ADB Toolbox tool window to a
 * known state with the test device selected, and fails the test if the plugin logged an ERROR while
 * it ran. On failure, a screenshot, the UI hierarchy and the plugin-log tail are saved under
 * `e2e/build/e2e-report/` (see docs/e2e-testing.md → "When a test fails").
 */
@ExtendWith(E2eTest.FailureArtifacts::class)
abstract class E2eTest {
    protected val studio: Studio get() = sharedStudio
    protected val pluginLog = PluginLog()
    protected lateinit var logMark: PluginLog.Mark

    @BeforeEach
    fun prepareIdeAndDevice() {
        assumeTrue(robotReachable(), "robot-server not reachable at ${E2eConfig.robotUrl}; start the environment with e2e/scripts/run-e2e.sh")
        check(Adb.isBooted()) { "device ${E2eConfig.serial} is not booted" }
        studio.closeDialogs()
        // Bottom tool windows (e.g. the Terminal from "Open shell") would shrink the plugin panel.
        listOf("Terminal", "Run", "Build", "Logcat").forEach(studio::hideToolWindow)
        studio.expireIdeNotifications()
        studio.openToolWindow()
        studio.dismissToasts()
        awaitSelectedDevice()
        logMark = pluginLog.mark()
    }

    @AfterEach
    fun pluginLoggedNoErrors() {
        if (::logMark.isInitialized) pluginLog.errorsSince(logMark).shouldBeEmpty()
    }

    /** Waits until the device bar shows [E2eConfig.serial] as the selected, online device. */
    protected fun awaitSelectedDevice(timeout: Duration = E2eConfig.deviceTimeout(15)) {
        awaitUntil(timeout, Duration.ofMillis(500), "device bar to show ${E2eConfig.serial}") {
            studio.isShowing("//div[@class='DeviceContextBarPanel']//div[@accessiblename='${E2eConfig.serial}']")
        }
    }

    private fun robotReachable(): Boolean =
        runCatching { URI(E2eConfig.robotUrl).toURL().openConnection().apply { connectTimeout = 2000 }.getInputStream().close() }.isSuccess

    companion object {
        val sharedStudio: Studio by lazy { Studio() }
    }

    class FailureArtifacts : TestWatcher {
        override fun testFailed(context: ExtensionContext, cause: Throwable?) {
            val name = "${context.requiredTestClass.simpleName}-${context.requiredTestMethod.name}".replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(E2eConfig.reportDir, "failures").apply { mkdirs() }
            runCatching { sharedStudio.screenshot(File(dir, "$name.png")) }
            runCatching { File(dir, "$name.hierarchy.html").writeText(URI("${E2eConfig.robotUrl}/hierarchy").toURL().readText()) }
            runCatching { File(dir, "$name.plugin-log.txt").writeText(E2eConfig.pluginLog.readLines().takeLast(300).joinToString("\n")) }
        }
    }
}
