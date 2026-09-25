package dev.acme.adbtoolbox.application.nav

import dev.acme.adbtoolbox.domain.nav.NavigationState
import dev.acme.adbtoolbox.domain.nav.ViewId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewEnterRefresherTest {

    private val scope = TestScope()
    private val navigation = MutableStateFlow<NavigationState>(NavigationState.Loading)
    private val refreshed = mutableListOf<ViewId>()

    private fun start() = ViewEnterRefresher(
        scope = scope,
        dispatcher = StandardTestDispatcher(scope.testScheduler),
        navigation = navigation,
        refreshers = mapOf(
            ViewId.Apps to { refreshed += ViewId.Apps },
            ViewId.Display to { refreshed += ViewId.Display },
        ),
    )

    @Test
    fun `entering a view re-reads its device state`() {
        // Regression (docs/e2e-testing.md): an app installed by Run in Android Studio never showed
        // up in Apps, and toggles changed on the device kept their old values in Display.
        start()
        navigation.value = NavigationState.Ready(ViewId.Device)
        scope.runCurrent()
        navigation.value = NavigationState.Ready(ViewId.Apps)
        scope.runCurrent()
        navigation.value = NavigationState.Ready(ViewId.Display)
        scope.runCurrent()

        refreshed shouldBe listOf(ViewId.Apps, ViewId.Display)
    }

    @Test
    fun `re-entering a view refreshes again, staying in it does not`() {
        start()
        navigation.value = NavigationState.Ready(ViewId.Apps)
        scope.runCurrent()
        navigation.value = NavigationState.Ready(ViewId.Apps)
        scope.runCurrent()
        navigation.value = NavigationState.Ready(ViewId.Logcat)
        scope.runCurrent()
        navigation.value = NavigationState.Ready(ViewId.Apps)
        scope.runCurrent()

        refreshed shouldBe listOf(ViewId.Apps, ViewId.Apps)
    }

    @Test
    fun `returning to the tool window refreshes only the view on screen`() {
        // Run in Android Studio installs the app while Apps is already open; coming back to the
        // tool window must pick it up without leaving the view.
        val refresher = start()
        navigation.value = NavigationState.Ready(ViewId.Apps)
        scope.runCurrent()

        refresher.refreshCurrent()
        scope.runCurrent()

        refreshed shouldBe listOf(ViewId.Apps, ViewId.Apps)
    }

    @Test
    fun `returning to the tool window on a view without device state does nothing`() {
        val refresher = start()
        navigation.value = NavigationState.Ready(ViewId.Logcat)
        scope.runCurrent()

        refresher.refreshCurrent()

        refreshed shouldBe emptyList()
    }
}
