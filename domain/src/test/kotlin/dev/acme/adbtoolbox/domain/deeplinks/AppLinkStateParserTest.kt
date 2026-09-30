package dev.acme.adbtoolbox.domain.deeplinks

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppLinkStateParserTest {
    @Test
    fun `maps platform and vendor domain states`() {
        val states = AppLinkStateParser.parse("""
            com.acme:
              Domain verification state:
                good.example: verified
                user.example: 1
                blocked.example: 2
                old.example: legacy_failure
                vendor.example: 1026
        """.trimIndent()).associateBy { it.host }

        states.getValue("good.example").deviceState shouldBe DeviceLinkState.VERIFIED
        states.getValue("user.example").deviceState shouldBe DeviceLinkState.APPROVED
        states.getValue("blocked.example").deviceState shouldBe DeviceLinkState.DENIED
        states.getValue("old.example").deviceState shouldBe DeviceLinkState.LEGACY_FAILURE
        states.getValue("vendor.example").let { it.deviceState shouldBe DeviceLinkState.VENDOR; it.deviceStateCode shouldBe 1026 }
    }
}
