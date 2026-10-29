package io.github.dkej123.devicecockpit.domain.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeepLinkCommandsTest {
    @Test
    fun `an unrestricted link uses the Android chooser and preserves URI shell characters`() {
        val uri = "myapp://product/42?ref=ide&name=O'Reilly#details"
        val request = DeepLinkCommands.open(DeviceSerial.of("wifi:5555"), "", uri)
        request.serial shouldBe DeviceSerial.of("wifi:5555")
        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "am start -W -a 'android.intent.action.VIEW' -c 'android.intent.category.BROWSABLE' -d 'myapp://product/42?ref=ide&name=O'\\''Reilly#details'"
    }

    @Test
    fun `URI validation rejects a missing destination and whitespace`() {
        for (uri in listOf("https:", "https://host/a b", "missing-scheme", "https://host/\n")) {
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                DeepLinkCommands.open(DeviceSerial.of("s"), "com.acme.app", uri)
            }
        }
    }

    @Test
    fun `open uses package restricted implicit browsable intent`() {
        val request = DeepLinkCommands.open(DeviceSerial.of("s"), "com.acme.app", "https://shop.example/p/1?x=a%20b")
        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "am start -W -a 'android.intent.action.VIEW' -c 'android.intent.category.BROWSABLE' -d 'https://shop.example/p/1?x=a%20b' -p 'com.acme.app'"
    }

    @Test
    fun `pattern matches scheme wildcard host port and path`() {
        val pattern = UriPattern(
            schemes = setOf("https"), hosts = setOf("*.example.com"), ports = setOf("8443"),
            paths = listOf(UriMatcher(UriMatcherKind.PATH_PREFIX, "/products/")),
        )
        pattern.matches("https://m.example.com:8443/products/42") shouldBe true
        pattern.matches("https://example.com:8443/products/42") shouldBe true
        pattern.matches("http://m.example.com:8443/products/42") shouldBe false
        pattern.matches("https://evil.example.org:8443/products/42") shouldBe false
    }

    @Test
    fun `browser intent scheme links are recognised so they are not sent as literal data`() {
        DeepLinkCommands.isBrowserIntentUri("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end") shouldBe true
        DeepLinkCommands.isBrowserIntentUri("INTENT:#Intent;action=android.intent.action.VIEW;end") shouldBe true
        DeepLinkCommands.isBrowserIntentUri("https://example.com/intent") shouldBe false
        DeepLinkCommands.isBrowserIntentUri("myapp://intent") shouldBe false
    }

    @Test
    fun `cold start force-stops the target before the launch`() {
        (DeepLinkCommands.open(DeviceSerial.of("s"), "com.acme", "a://b", coldStart = true).operation as AdbOperation.Shell).command.render() shouldBe
            "am start -W -S -a 'android.intent.action.VIEW' -c 'android.intent.category.BROWSABLE' -d 'a://b' -p 'com.acme'"
    }
}
