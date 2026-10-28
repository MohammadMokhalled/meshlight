package com.example.meshlightcontroller

import android.app.Application
import com.example.meshlightcontroller.core.mesh.MeshBleBearer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class BearerLifecycleTest {
    @Test fun disconnectBeforeAnyConnectionIsImmediateAndIdempotent() = runBlocking {
        val bearer=MeshBleBearer(RuntimeEnvironment.getApplication(),{_,_->},{})
        try {
            withTimeout(1000) {bearer.closeLink();bearer.closeLink()}
            assertFalse(bearer.isConnected)
            assertEquals(20,bearer.bearerMtu)
            try {
                withTimeout(1000) { bearer.keepAlive() }
                fail("A disconnected bearer cannot probe the link")
            } catch (expected: IllegalStateException) {
                assertEquals("Mesh proxy is not ready", expected.message)
            }
        } finally {bearer.close()}
    }
}
