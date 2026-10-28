package com.example.meshlightcontroller

import com.example.meshlightcontroller.core.control.*
import com.example.meshlightcontroller.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerTest {
    @Test fun interruptionRetainsCurrentFinalsAndResumesWithoutCancellationFailures() = runTest {
        val attempts = mutableListOf<EditKey>()
        val completed = mutableListOf<ScheduledEdit>()
        val scheduler = EditScheduler(backgroundScope, { edit ->
            attempts += edit.key
            if (attempts.size == 1) awaitCancellation()
        }, { edit, error -> assertNull(error); completed += edit })
        val active = EditKey("a", 0)
        val queued = EditKey("a", 1)
        scheduler.enqueue(active, LightingPatch(intensity = 19), true)
        runCurrent()
        scheduler.enqueue(queued, LightingPatch(power = false), true)
        scheduler.enqueue(EditKey("a", 2), LightingPatch(intensity = 60), false)
        val retained = scheduler.interrupt()
        runCurrent()
        assertEquals(listOf(active, queued), retained.map { it.key })
        assertTrue(completed.isEmpty())
        retained.forEach { scheduler.enqueue(it.key, it.patch, true) }
        runCurrent()
        assertEquals(listOf(queued, active), completed.map { it.key })
        assertEquals(listOf(active, queued, active), attempts)
    }

    @Test fun interruptionDoesNotResurrectFinalSupersededByPreview() = runTest {
        val scheduler = EditScheduler(backgroundScope, { awaitCancellation() }, { _, _ -> fail("Superseded completion") })
        val key = EditKey("a", 0)
        scheduler.enqueue(key, LightingPatch(intensity = 19), true)
        runCurrent()
        scheduler.enqueue(key, LightingPatch(intensity = 60), false)
        assertTrue(scheduler.interrupt().isEmpty())
        runCurrent()
    }

    @Test fun interruptionPreservesWholeStripPropertyOrdering() = runTest {
        val scheduler = EditScheduler(backgroundScope, {}, { _, _ -> })
        scheduler.enqueue(EditKey("a", 1), LightingPatch(Rgb(255, 0, 0), 25), true)
        scheduler.enqueue(EditKey("a", LedTarget.All), LightingPatch(rgb = Rgb(0, 0, 255)), true)
        val retained = scheduler.interrupt()
        assertEquals(listOf(1, LedTarget.All), retained.map { it.key.target })
        assertEquals(LightingPatch(intensity = 25), retained.first().patch)
        scheduler.cancel()
    }

    @Test fun coalescesIndependentPropertiesAndFinalIntent() = runTest {
        val sent = mutableListOf<ScheduledEdit>()
        val scheduler = EditScheduler(backgroundScope, { sent += it }, { _, error -> assertNull(error) })
        val key = EditKey("a", 0)
        scheduler.enqueue(key, LightingPatch(rgb = Rgb(255, 0, 0)), false)
        scheduler.enqueue(key, LightingPatch(intensity = 25), false)
        scheduler.enqueue(key, LightingPatch(intensity = 35), true)
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(LightingPatch(Rgb(255, 0, 0), 35), sent.single().patch)
        assertTrue(sent.single().final)
    }
    @Test fun newIntentCancelsSupersededAcknowledgedWork() = runTest {
        val sent = mutableListOf<LightingPatch>()
        val scheduler = EditScheduler(backgroundScope, { edit ->
            if (edit.patch.intensity == 10) delay(10_000)
            sent += edit.patch
        }, { _, _ -> })
        val key = EditKey("a", 0)
        scheduler.enqueue(key, LightingPatch(intensity = 10), true)
        runCurrent()
        scheduler.enqueue(key, LightingPatch(intensity = 90), true)
        runCurrent()
        assertEquals(listOf(LightingPatch(intensity = 90)), sent)
        assertEquals(0, currentTime)
    }
    @Test fun wholeStripEditsSupersedeOnlyOverlappingQueuedProperties() = runTest {
        val sent = mutableListOf<ScheduledEdit>()
        val scheduler = EditScheduler(backgroundScope, { sent += it }, { _, _ -> })
        scheduler.enqueue(EditKey("a", 1), LightingPatch(Rgb(255, 0, 0), 25), true)
        scheduler.enqueue(EditKey("a", LedTarget.All), LightingPatch(rgb = Rgb(0, 0, 255)), true)
        runCurrent()
        assertEquals(LightingPatch(intensity = 25), sent.first().patch)
        assertEquals(LedTarget.All, sent.last().key.target)
    }
    @Test fun outboundBudgetIsGlobalAndTidWrapIsSafe() = runTest {
        val times = mutableListOf<Long>()
        val pacer = OutboundPacer({ currentTime })
        val first = async { pacer.transmit { times += currentTime } }
        val second = async { pacer.transmit { times += currentTime } }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        first.await(); second.await()
        assertEquals(listOf(0L, 200L), times)
        var time = 0L
        val ids = TransactionIds { time }
        assertEquals(256, List(256) { ids.allocate() }.distinct().size)
        assertThrows(IllegalStateException::class.java) { ids.allocate() }
        time = 6000
        assertEquals(0, ids.allocate())
    }
}
