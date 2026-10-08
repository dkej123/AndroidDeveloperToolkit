package dev.acme.adbtoolbox.intellij.ui.capture

import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import dev.acme.adbtoolbox.intellij.icons.AdbToolboxIcons
import dev.acme.adbtoolbox.intellij.ui.common.ScreenToolButton
import dev.acme.adbtoolbox.intellij.ui.common.ToolButtonSegment
import dev.acme.adbtoolbox.intellij.ui.common.FlexRowLayout
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.capture.CaptureIntent
import dev.acme.adbtoolbox.application.capture.CaptureViewModel
import dev.acme.adbtoolbox.application.capture.CaptureViewState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Task 019's minimal functional Device-view binding: `design/IMPLEMENTATION.md` §4's
 * **"Screenshot"** button, plus ADR 0013's **"Full page"** button for the foreground app's whole
 * scrolling content — with no other Capture/Device-view chrome. Task 015 landed the Device view first, so this is not registered as its own
 * [dev.acme.adbtoolbox.intellij.host.FeatureViewHost] route; instead
 * `dev.acme.adbtoolbox.intellij.capture.CaptureCoordinator` mounts this panel into task 015's
 * already-registered [dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel.captureSlot] — the
 * "fold this control into its own layout" outcome this class's earlier revision anticipated. Task
 * 044 owns final visual styling.
 *
 * The Reveal action itself is not a persistent button here — `design/README.md`'s Capture section
 * only ever offers Reveal as the success toast's action (`design/IMPLEMENTATION.md` §7's "screenshot
 * ... with ... a Reveal action on the result toast"), which [CaptureViewModel] already wires; a
 * committed [dev.acme.adbtoolbox.domain.capture.CaptureLocation] existing is exactly what gates that
 * action, so a Reveal control can never point at a partial/failed/nonexistent file.
 *
 * Follows [dev.acme.adbtoolbox.intellij.feedback.FeedbackOverlayCoordinator]'s established shape:
 * [scope] is owned by the caller, state is collected and marshaled onto [dispatchers]' `main`
 * context before touching Swing, and [render] is `internal`/non-suspend for direct unit testing.
 */
class CaptureView(
    private val viewModel: CaptureViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : JBPanel<CaptureView>(FlexRowLayout(0)), Disposable {

    /** Where screenshots go, as shown to the user (see DeviceSectionMetaViewModel). */
    private var destinationLabel = "~/Desktop"
    private var lastState = CaptureViewState()

    /** The Screen toolbar's camera button (design §3): saves a PNG of the current screen. */
    val screenshotButton = ScreenToolButton(AdbToolboxIcons.Actions.screenshot, segment = ToolButtonSegment.MAIN).apply {
        getAccessibleContext().accessibleName = "Screenshot"
        toolTipText = screenshotTooltip()
        addActionListener { viewModel.handle(CaptureIntent.CaptureScreenshot) }
    }

    /** ADR 0013: the foreground app's whole scrolling content, captured on a tall virtual display. */
    val fullScreenshotItem = JBMenuItem("Full page").apply {
        toolTipText = fullScreenshotTooltip()
        addActionListener { viewModel.handle(CaptureIntent.CaptureFullScreenshot) }
    }

    private val moreMenu = JBPopupMenu().apply {
        add(fullScreenshotItem)
        addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) { moreButton.isOpen = true }
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) { moreButton.isOpen = false }
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) { moreButton.isOpen = false }
        })
    }

    /** The split button's caret (user decision, 2026-10-08: Full page lives in this menu). */
    val moreButton: ScreenToolButton = ScreenToolButton(null, segment = ToolButtonSegment.CARET).apply {
        getAccessibleContext().accessibleName = "More screenshot options"
        toolTipText = "More screenshots — Full page"
        addActionListener { moreMenu.show(this, 0, height) }
    }

    init {
        isOpaque = false
        add(screenshotButton)
        add(moreButton)

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: CaptureViewState) {
        lastState = state
        val enabled = state.controlPolicy is ControlPolicy.Enabled
        screenshotButton.isEnabled = enabled && !state.isCapturing
        moreButton.isEnabled = screenshotButton.isEnabled
        fullScreenshotItem.isEnabled = screenshotButton.isEnabled
        screenshotButton.toolTipText = if (enabled) screenshotTooltip() else "${screenshotTooltip()} — Connect a device to use this"
        fullScreenshotItem.toolTipText = if (enabled) fullScreenshotTooltip() else "${fullScreenshotTooltip()} — Connect a device to use this"
    }

    private fun screenshotTooltip() = "Screenshot — save a PNG to $destinationLabel"

    private fun fullScreenshotTooltip() =
        "Save the app's whole scrolling content as a PNG to $destinationLabel. " +
            "The app is briefly moved to a tall virtual screen, so its current screen is recreated."

    /** Must be called on the EDT. */
    fun setDestinationLabel(label: String) {
        destinationLabel = label
        render(lastState)
    }

    override fun dispose() {
        scope.cancel()
    }
}
