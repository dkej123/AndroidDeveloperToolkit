package io.github.dkej123.devicecockpit.domain.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeepLinkCommandsTest {
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
}
