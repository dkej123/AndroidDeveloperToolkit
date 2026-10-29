package io.github.dkej123.devicecockpit.domain.deeplinks

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `am start -W` output as printed by ActivityManagerShellCommand (verified on a Samsung S23, Android 16). */
class AmStartOutputTest {
    @Test
    fun `a cold or warm launch names the activity that handled the link`() {
        val output = AmStartOutput.parse(
            """
            Starting: Intent { act=android.intent.action.VIEW cat=[android.intent.category.BROWSABLE] dat=https://example.com/... }
            Status: ok
            LaunchState: WARM
            Activity: com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity
            TotalTime: 196
            Complete
            """.trimIndent(),
        )
        output.ok shouldBe true
        output.error shouldBe null
        output.handlerPackage shouldBe "com.android.chrome"
        output.chooserShown shouldBe false
        output.deliveredToRunningInstance shouldBe false
    }

    @Test
    fun `the system chooser means no single app was resolved`() {
        for (activity in listOf("android/com.android.internal.app.ResolverActivity", "com.android.intentresolver/.ChooserActivity")) {
            val output = AmStartOutput.parse("Status: ok\nActivity: $activity\nComplete")
            output.chooserShown shouldBe true
            output.handlerPackage shouldBe null
        }
    }

    @Test
    fun `a link delivered to the running top activity is reported as onNewIntent`() {
        val output = AmStartOutput.parse(
            "Starting: Intent { act=android.intent.action.VIEW }\n" +
                "Warning: Activity not started, intent has been delivered to currently running top-most instance.\n" +
                "Status: ok\nLaunchState: UNKNOWN (0)\nActivity: com.acme.shop/.MainActivity\nComplete",
        )
        output.ok shouldBe true
        output.deliveredToRunningInstance shouldBe true
        output.handlerPackage shouldBe "com.acme.shop"
    }

    @Test
    fun `errors and non-ok statuses are failures`() {
        AmStartOutput.parse("Error: Activity not started, unable to resolve Intent { act=android.intent.action.VIEW }").let {
            it.ok shouldBe false
            it.unresolved shouldBe true
        }
        AmStartOutput.parse("Starting: Intent {}\nStatus: timeout\nComplete").let {
            it.ok shouldBe false
            it.error shouldBe "Status: timeout"
        }
    }

    @Test
    fun `launch state and total time describe the start`() {
        val output = AmStartOutput.parse("Status: ok\nLaunchState: COLD\nActivity: com.acme/.Main\nTotalTime: 412\nWaitTime: 420\nComplete")
        output.launchState shouldBe "COLD"
        output.totalTimeMs shouldBe 412L
        AmStartOutput.parse("Status: ok\nLaunchState: UNKNOWN (0)\nComplete").launchState shouldBe "UNKNOWN"
    }
}
