package io.github.dkej123.devicecockpit.intellij.ui.mirroring

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.scale.JBUIScale
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * Opened by the Mirror button while idle (user request 2026-10-14, replacing the split button's
 * caret): the [MirroringOptionsPanel] — whose edits are saved as they are made — and "Start
 * mirroring", the default action. Cancel closes it without starting; the options stay saved.
 */
internal class MirroringStartDialog(
    parent: Component,
    private val optionsPanel: MirroringOptionsPanel,
) : DialogWrapper(parent, false) {

    init {
        title = "Start Mirroring"
        setOKButtonText("Start mirroring")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = EmptyBorder(JBUIScale.scale(4), 0, JBUIScale.scale(4), 0)
        preferredSize = Dimension(JBUIScale.scale(360), optionsPanel.apply { isVisible = true }.preferredSize.height + JBUIScale.scale(8))
        add(optionsPanel, BorderLayout.CENTER)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    internal val startActionForTest: Action get() = okAction

    override fun getPreferredFocusedComponent(): JComponent? = getButton(okAction)
}
