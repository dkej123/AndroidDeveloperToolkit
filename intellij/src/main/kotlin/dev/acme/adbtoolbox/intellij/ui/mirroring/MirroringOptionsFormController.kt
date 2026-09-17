package dev.acme.adbtoolbox.intellij.ui.mirroring

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.acme.adbtoolbox.application.mirroring.MirroringOptionsApplyResult
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionFieldError
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsDraft
import dev.acme.adbtoolbox.domain.mirroring.toDraft
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import javax.swing.JPanel

/** Task 040's editor backend seam — mirrors [dev.acme.adbtoolbox.intellij.settings.SettingsEditorBackend]:
 * a real project reads/writes through [dev.acme.adbtoolbox.intellij.persistence.MirroringOptionsPersistenceAdapter]
 * and [dev.acme.adbtoolbox.application.mirroring.MirroringOptionsUseCase], while tests substitute a fake. */
internal interface MirroringOptionsEditorBackend {
    fun read(): MirroringOptions
    fun apply(candidate: MirroringOptionsDraft): MirroringOptionsApplyResult
}

/**
 * Owns task 040's native options form and its apply/reset/cancel behavior, independent of
 * [MirroringOptionsDialog]'s [com.intellij.openapi.ui.DialogWrapper] plumbing — the same
 * "testable controller behind thin platform glue" split
 * [dev.acme.adbtoolbox.intellij.settings.AdbToolboxSettingsConfigurable] uses for its own form, kept
 * separate here because [com.intellij.openapi.ui.DialogWrapper]'s own `init()`/peer lifecycle is not
 * something a plain unit test should need to drive.
 *
 * Cancelling is simply *not calling* [apply] before disposal — this controller never writes to
 * [backend] on its own, so a dialog's Cancel button (or Escape, or closing the window) can never
 * persist an edited-but-unapproved draft (task 040's acceptance criterion: "invalid options do not
 * persist/start", and by construction here, neither does an unapplied valid one).
 */
internal class MirroringOptionsFormController(private val backend: MirroringOptionsEditorBackend) {

    private var persisted: MirroringOptions = backend.read()

    val stayAwake: JBCheckBox = JBCheckBox("Stay awake").apply { name = "mirroringStayAwakeCheckBox" }
    val showTouches: JBCheckBox = JBCheckBox("Show touches").apply { name = "mirroringShowTouchesCheckBox" }
    val maxSize: JBTextField = optionField("mirroringMaxSizeField")
    val videoBitRateMbps: JBTextField = optionField("mirroringVideoBitRateField")

    var errorMessage: String? = null
        private set

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addComponent(stayAwake)
        .addComponent(showTouches)
        .addLabeledComponent("Max size (px):", maxSize, 1, false)
        .addTooltip("Leave blank for no limit. Allowed range: ${MirroringOptions.MIN_MAX_SIZE_PX}–${MirroringOptions.MAX_MAX_SIZE_PX}.")
        .addLabeledComponent("Video bit rate (Mbps):", videoBitRateMbps, 1, false)
        .addTooltip(
            "Leave blank for scrcpy's default. Allowed range: ${MirroringOptions.MIN_VIDEO_BIT_RATE_MBPS}–" +
                "${MirroringOptions.MAX_VIDEO_BIT_RATE_MBPS}.",
        )
        .addComponentFillVertically(JPanel(), 0)
        .panel

    init {
        writeToForm(persisted)
    }

    fun reset() {
        persisted = backend.read()
        writeToForm(persisted)
        errorMessage = null
    }

    /** Parses the form, validates+persists via [backend], and reports whether the dialog may now
     * close. On success, re-syncs the form/[persisted] baseline from the *validated* result —
     * matching [dev.acme.adbtoolbox.intellij.settings.AdbToolboxSettingsConfigurable.apply]'s
     * post-apply sync. A malformed (non-numeric) field is rejected locally, before ever reaching
     * [backend], the same "reject before it can persist" shape [validateMirroringOptions] itself
     * uses for out-of-range values.
     */
    fun apply(): Boolean {
        val maxSizeField = maxSize.text.parseOptionalInt("Max size")
        val bitRateField = videoBitRateMbps.text.parseOptionalInt("Video bit rate")
        val firstError = (maxSizeField as? ParsedField.Malformed)?.message
            ?: (bitRateField as? ParsedField.Malformed)?.message
        if (firstError != null) {
            errorMessage = firstError
            return false
        }

        val candidate = MirroringOptionsDraft(
            stayAwake = stayAwake.isSelected,
            showTouches = showTouches.isSelected,
            maxSize = (maxSizeField as ParsedField.Present).value,
            videoBitRateMbps = (bitRateField as ParsedField.Present).value,
        )

        return when (val result = backend.apply(candidate)) {
            is MirroringOptionsApplyResult.Applied -> {
                persisted = result.options
                writeToForm(persisted)
                errorMessage = null
                true
            }

            is MirroringOptionsApplyResult.Invalid -> {
                errorMessage = result.errors.toMessage()
                false
            }
        }
    }

    private fun writeToForm(options: MirroringOptions) {
        val draft = options.toDraft()
        stayAwake.isSelected = draft.stayAwake
        showTouches.isSelected = draft.showTouches
        maxSize.text = draft.maxSize?.toString().orEmpty()
        videoBitRateMbps.text = draft.videoBitRateMbps?.toString().orEmpty()
    }

    private sealed interface ParsedField {
        data class Present(val value: Int?) : ParsedField
        data class Malformed(val message: String) : ParsedField
    }

    private fun String.parseOptionalInt(label: String): ParsedField {
        val trimmed = trim()
        if (trimmed.isEmpty()) return ParsedField.Present(null)
        val value = trimmed.toIntOrNull() ?: return ParsedField.Malformed("$label must be a whole number.")
        return ParsedField.Present(value)
    }

    companion object {
        private fun optionField(componentName: String): JBTextField = JBTextField().apply {
            name = componentName
            columns = 8
            // Pixel/bitrate magnitudes read the same way as the design system's mono density
            // fields, matching task 046's DisplayPanel custom-field font choice.
            font = AdbToolboxTheme.Typography.mono
        }

        private fun Set<MirroringOptionFieldError>.toMessage(): String = map { error ->
            when (error) {
                MirroringOptionFieldError.MaxSizeOutOfRange ->
                    "Max size must be between ${MirroringOptions.MIN_MAX_SIZE_PX} and ${MirroringOptions.MAX_MAX_SIZE_PX} pixels."

                MirroringOptionFieldError.VideoBitRateOutOfRange ->
                    "Video bit rate must be between ${MirroringOptions.MIN_VIDEO_BIT_RATE_MBPS} and " +
                        "${MirroringOptions.MAX_VIDEO_BIT_RATE_MBPS} Mbps."
            }
        }.joinToString("\n")
    }
}
