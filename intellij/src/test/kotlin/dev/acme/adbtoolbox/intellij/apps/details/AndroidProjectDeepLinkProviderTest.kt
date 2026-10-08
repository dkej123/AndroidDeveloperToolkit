package dev.acme.adbtoolbox.intellij.apps.details

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class AndroidProjectDeepLinkProviderTest : BasePlatformTestCase() {

    interface SourceProvider {
        val resDirectories: Collection<File>
    }

    // Private implementation classes, like the Android plugin's own model implementations.
    private class FakeSourceProvider(override val resDirectories: Collection<File>) : SourceProvider

    private class FakeModel(val activeSourceProviders: List<SourceProvider>)

    fun `test res directories of every active source provider are collected`() {
        val model = FakeModel(
            listOf(
                FakeSourceProvider(listOf(File("/app/src/main/res"))),
                FakeSourceProvider(listOf(File("/app/src/debug/res"), File("/app/src/debug/res2"))),
            ),
        )

        val dirs = AndroidProjectDeepLinkProvider(project).activeResDirectories(model)

        assertEquals(listOf("/app/src/main/res", "/app/src/debug/res", "/app/src/debug/res2"), dirs.map { it.path })
    }

    fun `test a model without source providers yields no directories instead of throwing`() {
        assertEquals(emptyList<File>(), AndroidProjectDeepLinkProvider(project).activeResDirectories(Any()))
    }
}
