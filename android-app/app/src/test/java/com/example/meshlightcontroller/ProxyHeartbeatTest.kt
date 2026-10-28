package com.example.meshlightcontroller

import com.example.meshlightcontroller.core.mesh.ProxyHeartbeat
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyHeartbeatTest {
    @Test fun probesIdleConnectionAndStopsWithTheSession() = runTest {
        var probes = 0
        val heartbeat = ProxyHeartbeat(backgroundScope, { probes++ }, { fail("Healthy probe failed") })
        heartbeat.start()
        runCurrent()
        advanceTimeBy(1999); runCurrent()
        assertEquals(0, probes)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, probes)
        heartbeat.stop()
        advanceTimeBy(6000); runCurrent()
        assertEquals(1, probes)
    }

    @Test fun startingAgainCancelsTheOldLinkProbe() = runTest {
        var attempts = 0
        var cancelled = 0
        val heartbeat = ProxyHeartbeat(backgroundScope, {
            attempts++
            try { awaitCancellation() } finally { cancelled++ }
        }, { fail("Cancellation is not a link failure") })
        heartbeat.start()
        advanceTimeBy(2000); runCurrent()
        assertEquals(1, attempts)
        heartbeat.start()
        runCurrent()
        assertEquals(1, cancelled)
        advanceTimeBy(2000); runCurrent()
        assertEquals(2, attempts)
        heartbeat.stop(); runCurrent()
        assertEquals(2, cancelled)
    }

    @Test fun failedProbeRequestsOneRecoveryAndStops() = runTest {
        var failures = 0
        val heartbeat = ProxyHeartbeat(backgroundScope, { error("GATT unavailable") }, { failures++ })
        heartbeat.start()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, failures)
    }
}
