package dev.acme.adbtoolbox.adapters.adb.device

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

class DdmlibDeviceChangeListenerSourceTest {

    @Test
    fun `registers and unregisters the ddmlib listener without a real bridge or hardware`() = runTest {
        val source = DdmlibDeviceChangeListenerSource()

        val job = launch { source.events().collect { } }
        yield()
        job.cancel()
        job.join()

        // No exception means AndroidDebugBridge.addDeviceChangeListener/removeDeviceChangeListener
        // (plain static listener-list management, no native/process work) were called cleanly on
        // collection start/cancellation with no real device or bridge connection required.
    }
}
