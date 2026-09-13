package dev.acme.adbtoolbox.intellij.ui.mirroring

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.ThrowableComputable
import dev.acme.adbtoolbox.application.mirroring.MirroringOptionsApplyResult
import dev.acme.adbtoolbox.application.mirroring.MirroringOptionsUseCase
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsDraft
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService
import dev.acme.adbtoolbox.intellij.persistence.AdbToolboxProjectState
import dev.acme.adbtoolbox.intellij.persistence.MirroringOptionsPersistenceAdapter
import kotlinx.coroutines.runBlocking
import javax.swing.JComponent

/**
 * Task 040's native options flow: the design's "22px options icon button" opens this directly from
 * the Device view's Mirroring section (`design/README.md` §3) — not IntelliJ's Settings — so it is a
 * plain [DialogWrapper], not a `Configurable`. All actual read/validate/apply/reset behavior lives in
 * [MirroringOptionsFormController] (unit-tested on its own); this class is intentionally thin glue
 * over [DialogWrapper]'s own OK/Cancel/dispose lifecycle, the same boundary
 * [dev.acme.adbtoolbox.intellij.settings.AdbToolboxSettingsConfigurable] draws around its own form.
 */
class MirroringOptionsDialog internal constructor(
    project: Project,
    backend: MirroringOptionsEditorBackend,
) : DialogWrapper(project, false) {

    constructor(project: Project) : this(project, ProjectMirroringOptionsEditorBackend(project))

    private val controller = MirroringOptionsFormController(backend)

    init {
        title = "Mirroring Options"
        init()
    }

    override fun createCenterPanel(): JComponent = controller.panel

    override fun doOKAction() {
        if (controller.apply()) {
            super.doOKAction()
        } else {
            setErrorText(controller.errorMessage)
        }
    }

    override fun doCancelAction() {
        // No apply() call on this path (Cancel, Escape, or the window's own close box) — the
        // controller never writes to its backend on its own, so nothing persists.
        super.doCancelAction()
    }
}

private class ProjectMirroringOptionsEditorBackend(
    private val project: Project,
) : MirroringOptionsEditorBackend {
    private val persistence = MirroringOptionsPersistenceAdapter(project.service<AdbToolboxProjectState>())
    private val useCase: MirroringOptionsUseCase
        get() = project.service<AdbToolboxProjectService>().mirroringOptionsUseCase

    override fun read(): MirroringOptions = persistence.readOptionsNow()

    override fun apply(candidate: MirroringOptionsDraft): MirroringOptionsApplyResult =
        runModal("Applying Mirroring Options") { useCase.apply(candidate) }

    private fun <T> runModal(title: String, operation: suspend () -> T): T =
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<T, RuntimeException> { runBlocking { operation() } },
            title,
            true,
            project,
        )
}
