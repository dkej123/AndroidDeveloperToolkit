package io.github.dkej123.devicecockpit.intellij.marketplace

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginDescriptionTest : BasePlatformTestCase() {

    fun `test the description starts with forty Latin letters or spaces as Marketplace requires`() {
        // Regression (v1.2.0 upload, 2026-10-13): Marketplace rejected "ADB device control for
        // Android™ …" with "The plugin description must start with Latin characters and have at
        // least 40 characters" — the ™ fell inside the first 40 characters of the text.
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        val html = Regex("""<description><!\[CDATA\[(.*?)]]></description>""", RegexOption.DOT_MATCHES_ALL).find(pluginXml)!!.groupValues[1]
        val text = html.replace(Regex("<[^>]+>"), " ").replace(Regex("&[a-z]+;"), "™").replace(Regex("\\s+"), " ").trim()

        val start = text.take(40)
        assertTrue("Starts with \"$start\"", start.length == 40 && start.all { it in 'A'..'Z' || it in 'a'..'z' || it == ' ' })
    }
}
