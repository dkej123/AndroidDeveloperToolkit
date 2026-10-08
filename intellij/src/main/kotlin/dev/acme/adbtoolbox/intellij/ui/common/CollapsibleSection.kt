package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.scale.JBUIScale
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Which view sections are collapsed (`design/README.md` §3, "Collapsible"): one application-level
 * [PropertiesComponent] value, so the choice is the same in every project and for every device and
 * survives IDE restarts. Keys are the design's section keys (`app`, `screen`, `device`, …).
 */
class CollapsedSectionsStore(private val properties: () -> PropertiesComponent = PropertiesComponent::getInstance) {

    fun isCollapsed(key: String): Boolean = key in read()

    fun setCollapsed(key: String, collapsed: Boolean) {
        val keys = read().toMutableSet().apply { if (collapsed) add(key) else remove(key) }
        if (keys.isEmpty()) properties().unsetValue(KEY) else properties().setValue(KEY, keys.sorted().joinToString(","))
    }

    private fun read(): Set<String> =
        properties().getValue(KEY).orEmpty().split(',').filter(String::isNotBlank).toSet()

    companion object {
        const val KEY = "adbToolbox.collapsedSections"
    }
}

/**
 * A [DesignSections.section] whose header starts with a chevron: clicking the chevron or the title
 * folds the section to its header row; header meta and links stay visible and do not toggle. The
 * rows below the header move into one body panel that is hidden as a whole, so rows that manage
 * their own visibility (errors, disclosures) keep it across a collapse.
 */
class CollapsibleSection private constructor(
    private val key: String,
    private val store: CollapsedSectionsStore,
    private val body: JPanel,
    val chevron: Chevron,
) {
    var isExpanded: Boolean = true
        private set

    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        body.isVisible = expanded
        chevron.expanded = expanded
        chevron.toolTipText = if (expanded) "Collapse section" else "Expand section"
        store.setCollapsed(key, !expanded)
        body.parent?.let { it.revalidate(); it.repaint() }
    }

    /** `chevStyle`: a 4×7 `textDim` triangle in a 7×8 box, pointing down (rotated 90°) while open. */
    class Chevron : JComponent() {
        var expanded: Boolean = true
            set(value) {
                field = value
                repaint()
            }

        init {
            preferredSize = Dimension(JBUIScale.scale(7), JBUIScale.scale(8))
            minimumSize = preferredSize
            maximumSize = preferredSize
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = AdbToolboxTheme.Colors.textDim
                val long = JBUIScale.scale(7f)
                val short = JBUIScale.scale(4f)
                val cx = width / 2f
                val cy = height / 2f
                val triangle = Path2D.Float().apply {
                    if (expanded) {
                        moveTo(cx - long / 2, cy - short / 2); lineTo(cx + long / 2, cy - short / 2); lineTo(cx, cy + short / 2)
                    } else {
                        moveTo(cx - short / 2, cy - long / 2); lineTo(cx + short / 2, cy); lineTo(cx - short / 2, cy + long / 2)
                    }
                    closePath()
                }
                g2.fill(triangle)
            } finally {
                g2.dispose()
            }
        }
    }

    internal companion object {
        fun install(section: JPanel, key: String, title: JComponent, store: CollapsedSectionsStore): CollapsibleSection {
            val header = title.parent as JPanel
            val index = header.components.indexOf(title)
            val constraint = (header.layout as? FlexRowLayout)?.constraintOf(title)
            val chevron = Chevron()
            header.remove(title)
            val toggle = flexRow(AdbToolboxTheme.Spacing.s3, chevron, title, fill = title.takeIf { constraint == FlexRowLayout.FILL })
            header.add(toggle, constraint, index)

            // Everything after the header row (including the gap strut before the first body row).
            val headerRow = generateSequence<Component>(header) { it.parent?.takeIf { parent -> parent !== section } }.last()
            val rest = section.components.dropWhile { it !== headerRow }.drop(1)
            val body = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            rest.forEach { section.remove(it); body.add(it) }
            section.add(body)

            val collapsible = CollapsibleSection(key, store, body, chevron)
            val onClick = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = collapsible.setExpanded(!collapsible.isExpanded)
            }
            chevron.addMouseListener(onClick)
            title.addMouseListener(onClick)
            title.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            collapsible.setExpanded(!store.isCollapsed(key))
            return collapsible
        }
    }
}
