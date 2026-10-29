package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.contextModality
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * Dispatchers.EDT without a modality element runs as nonModal(), i.e. only after the modal
 * dialog closes — the app list, Open feedback and closing on success never reached the dialog.
 */
class DeepLinkDialogScopeTest : BasePlatformTestCase() {
    fun testDialogCoroutinesResumeOnTheEdtWhileTheModalDialogIsOpen() {
        val parent = CoroutineScope(Job())
        val scope = deepLinkDialogScope(parent)
        assertEquals(ModalityState.any(), scope.coroutineContext.contextModality())
        parent.cancel()
        assertFalse("Disposing the project scope also stops the dialog's coroutines", scope.coroutineContext[Job]!!.isActive)
    }
}
