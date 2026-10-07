package dev.acme.adbtoolbox.intellij.display

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.intellij.ui.common.ToggleSwitch

/** `repeat(auto-fill, minmax(150px, 1fr))` with a 5px gap inside the section's 10px inset. */
class QuickToggleGridTest : BasePlatformTestCase() {

    private fun tiles(count: Int) = List(count) { QuickToggleTile("Tile $it", ToggleSwitch(compact = true), JBLabel("off")) }

    fun `test column count follows the available width with a minimum of one`() {
        val grid = QuickToggleGrid(tiles(4))
        val inset = JBUI.scale(10) * 2

        assertEquals(1, grid.columnsFor(inset + JBUI.scale(120)))
        assertEquals(1, grid.columnsFor(inset + JBUI.scale(304)))
        assertEquals(2, grid.columnsFor(inset + JBUI.scale(305)))
        assertEquals(3, grid.columnsFor(inset + JBUI.scale(460)))
    }

    fun `test tiles fill equal columns and wrap into rows`() {
        val grid = QuickToggleGrid(tiles(4))
        val width = JBUI.scale(10) * 2 + JBUI.scale(305)
        grid.setSize(width, 400)
        grid.doLayout()

        val bounds = grid.components.map { it.bounds }
        assertEquals(bounds[0].width, bounds[1].width)
        assertEquals(bounds[0].y, bounds[1].y)
        assertTrue(bounds[2].y > bounds[0].y)
        assertEquals(bounds[0].x, bounds[2].x)
        assertEquals(2, (grid.preferredSize.height - grid.insets.top - grid.insets.bottom + JBUI.scale(5)) / (bounds[0].height + JBUI.scale(5)))
    }

    fun `test the tile keeps its value next to the caption for accessibility lookups`() {
        val value = JBLabel("on")
        val tile = QuickToggleTile("Stay awake", ToggleSwitch(compact = true), value)

        val labels = tile.components.filterIsInstance<JBLabel>()
        assertEquals("Stay awake", labels[0].accessibleContext.accessibleName)
        assertSame(value, tile.components[1])
    }
}
