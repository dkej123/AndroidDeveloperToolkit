package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import java.awt.event.MouseEvent
import javax.swing.JPanel

class CollapsibleSectionsTest : BasePlatformTestCase() {

    private lateinit var store: CollapsedSectionsStore

    override fun setUp() {
        super.setUp()
        PropertiesComponent.getInstance().unsetValue(CollapsedSectionsStore.KEY)
        store = CollapsedSectionsStore()
    }

    override fun tearDown() {
        try {
            // Application-level state: never leak a collapsed section into other tests' renderings.
            PropertiesComponent.getInstance().unsetValue(CollapsedSectionsStore.KEY)
        } finally {
            super.tearDown()
        }
    }

    private fun sectionWithBody(title: JBLabel, meta: JBLabel, vararg body: JPanel): JPanel =
        DesignSections.section(DesignSections.header(title, meta), *body)

    fun `test a section starts expanded and collapses its body but not its header on a title click`() {
        val title = DesignSections.titleLabel("Device")
        val meta = DesignSections.metaLabel("Pixel 9")
        val row = JPanel()
        val section = sectionWithBody(title, meta, row)
        val toggle = DesignSections.makeCollapsible(section, "device", title, store)

        assertTrue(toggle.isExpanded)
        assertTrue(row.isShowingInSection())

        click(title)

        assertFalse(toggle.isExpanded)
        assertFalse(row.isShowingInSection())
        assertTrue(meta.isVisible)
        assertEquals("Expand section", toggle.chevron.toolTipText)
    }

    fun `test the collapsed state is stored per key and restored by a new section`() {
        val title = DesignSections.titleLabel("Recent")
        DesignSections.makeCollapsible(sectionWithBody(title, DesignSections.metaLabel(), JPanel()), "recent", title, store)
        click(title)

        val restoredTitle = DesignSections.titleLabel("Recent")
        val body = JPanel()
        val restored = DesignSections.makeCollapsible(
            sectionWithBody(restoredTitle, DesignSections.metaLabel(), body), "recent", restoredTitle, CollapsedSectionsStore(),
        )
        val otherTitle = DesignSections.titleLabel("Proxy")
        val other = DesignSections.makeCollapsible(
            sectionWithBody(otherTitle, DesignSections.metaLabel(), JPanel()), "proxy", otherTitle, CollapsedSectionsStore(),
        )

        assertFalse(restored.isExpanded)
        assertFalse(body.isShowingInSection())
        assertTrue(other.isExpanded)
    }

    fun `test expanding keeps a body row's own visibility`() {
        val title = DesignSections.titleLabel("Display")
        val hiddenError = JPanel().apply { isVisible = false }
        val toggle = DesignSections.makeCollapsible(
            sectionWithBody(title, DesignSections.metaLabel(), JPanel(), hiddenError), "display", title, store,
        )

        click(toggle.chevron)
        click(toggle.chevron)

        assertTrue(toggle.isExpanded)
        assertFalse(hiddenError.isVisible)
    }

    fun `test clicking the header meta does not toggle`() {
        val title = DesignSections.titleLabel("Screen")
        val meta = DesignSections.metaLabel("~/Desktop")
        val toggle = DesignSections.makeCollapsible(sectionWithBody(title, meta, JPanel()), "screen", title, store)

        click(meta)

        assertTrue(toggle.isExpanded)
    }

    private fun click(component: java.awt.Component) {
        component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, 1, false, MouseEvent.BUTTON1))
    }

    /** Visible itself and in every ancestor up to the section. */
    private fun java.awt.Component.isShowingInSection(): Boolean {
        var current: java.awt.Component? = this
        while (current != null) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return true
    }
}
