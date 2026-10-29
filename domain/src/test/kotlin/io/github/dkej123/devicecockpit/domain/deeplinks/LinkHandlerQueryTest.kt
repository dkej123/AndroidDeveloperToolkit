package io.github.dkej123.devicecockpit.domain.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `cmd package query-activities` / `resolve-activity` output, captured on a Samsung S23 (Android 16). */
class LinkHandlerQueryTest {
    private val serial = DeviceSerial.of("R5CW72KKR4E")

    @Test
    fun `queries ask for VIEW + BROWSABLE without a package and never launch`() {
        (LinkHandlerQuery.matches(serial, "geo:52.2,21.0").operation as AdbOperation.Shell).command.render() shouldBe
            "cmd package query-activities --brief -a 'android.intent.action.VIEW' -c 'android.intent.category.BROWSABLE' -d 'geo:52.2,21.0'"
        (LinkHandlerQuery.resolve(serial, "a://b?x=1&y='z'").operation as AdbOperation.Shell).command.render() shouldBe
            "cmd package resolve-activity --brief -a 'android.intent.action.VIEW' -c 'android.intent.category.BROWSABLE' -d 'a://b?x=1&y='\\''z'\\'''"
    }

    @Test
    fun `query-activities lists one component per match`() {
        LinkHandlerQuery.parseMatches(
            """
            2 activities found:
              Activity #0:
                priority=0 preferredOrder=0 match=0x508000 specificIndex=-1 isDefault=true
                com.google.android.youtube/.UrlActivity
              Activity #1:
                priority=0 preferredOrder=0 match=0x208000 specificIndex=-1 isDefault=true
                com.android.chrome/com.google.android.apps.chrome.IntentDispatcher
            """.trimIndent(),
        ) shouldBe listOf("com.google.android.youtube/.UrlActivity", "com.android.chrome/com.google.android.apps.chrome.IntentDispatcher")
        LinkHandlerQuery.parseMatches("No activities found") shouldBe emptyList()
    }

    @Test
    fun `resolve-activity tells a direct handler from the chooser and from nothing`() {
        LinkHandlerQuery.parseResolved(
            "priority=0 preferredOrder=0 match=0x208000 specificIndex=-1 isDefault=true\ncom.google.android.gm/.ComposeActivityGmailExternal",
        ) shouldBe ResolvedHandler.Direct("com.google.android.gm/.ComposeActivityGmailExternal")
        LinkHandlerQuery.parseResolved(
            "priority=0 preferredOrder=0 match=0x0 specificIndex=-1 isDefault=false\nandroid/com.android.internal.app.ResolverActivity",
        ) shouldBe ResolvedHandler.Chooser
        LinkHandlerQuery.parseResolved("No activity found") shouldBe ResolvedHandler.None
    }
}
