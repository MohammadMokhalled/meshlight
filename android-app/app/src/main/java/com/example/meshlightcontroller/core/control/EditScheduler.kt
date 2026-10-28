package com.example.meshlightcontroller.core.control

import com.example.meshlightcontroller.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EditKey(val nodeUuid: String, val target: Int)
data class ScheduledEdit(val key: EditKey, val patch: LightingPatch, val final: Boolean, val revision: Long)

class EditScheduler(
    private val scope: CoroutineScope,
    private val send: suspend (ScheduledEdit) -> Unit,
    private val finished: (ScheduledEdit, Throwable?) -> Unit
) {
    private val pending = LinkedHashMap<EditKey, ScheduledEdit>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L
    private val revisions = mutableMapOf<EditKey, Long>()
    private var active: Pair<ScheduledEdit, Deferred<Unit>>? = null

    init {
        scope.launch {
            for (ignored in signal) {
                while (true) {
                    val edit = synchronized(pending) {
                        val eligible = pending.values.filter { candidate ->
                            pending.values.none { earlier ->
                                earlier.revision < candidate.revision && earlier.key.nodeUuid == candidate.key.nodeUuid &&
                                    overlaps(earlier.key.target, candidate.key.target) && intersects(earlier.patch, candidate.patch)
                            }
                        }
                        val next = eligible.firstOrNull { it.patch.power != null } ?: eligible.firstOrNull()
                        next?.also { pending.remove(it.key) }
                    } ?: break
                    if (!isCurrent(edit)) continue
                    val task = scope.async(start = CoroutineStart.LAZY) { send(edit) }
                    synchronized(pending) { active = edit to task }
                    val failure = try { task.start(); task.await(); null }
                    catch (cancel: CancellationException) { currentCoroutineContext().ensureActive(); cancel }
                    catch (error: Exception) { error }
                    finally { synchronized(pending) { if (active?.second === task) active = null } }
                    if (isCurrent(edit)) finished(edit, failure)
                }
            }
        }
    }

    fun enqueue(key: EditKey, patch: LightingPatch, final: Boolean): Long {
        require(!patch.isEmpty)
        val next = synchronized(pending) {
            require(pending.size < 512 || key in pending) { "Too many pending targets" }
            if (key.target == LedTarget.All) {
                pending.values.toList().filter { it.key.nodeUuid == key.nodeUuid && it.key != key }.forEach { previous ->
                    val remaining = previous.patch.without(patch)
                    if (remaining.isEmpty) {
                        pending.remove(previous.key)
                        revisions[previous.key] = ++revision
                    } else pending[previous.key] = previous.copy(patch = remaining)
                }
            }
            val id = ++revision
            revisions[key] = id
            val existing = pending[key]
            pending[key] = ScheduledEdit(key, existing?.patch?.merge(patch) ?: patch, final || existing?.final == true, id)
            if (active?.first?.key == key) active?.second?.cancel()
            id
        }
        signal.trySend(Unit)
        return next
    }

    fun isCurrent(edit: ScheduledEdit): Boolean = synchronized(pending) { revisions[edit.key] == edit.revision }
    fun supersede(key: EditKey): Long = synchronized(pending) {
        pending.remove(key)
        if (active?.first?.key == key) active?.second?.cancel()
        (++revision).also { revisions[key] = it }
    }
    fun cancel() = synchronized(pending) {
        pending.clear()
        active?.second?.cancel()
        revisions.keys.toList().forEach { revisions[it] = ++revision }
    }
    // Retain final intent for reconnect while cancelling packets tied to the old link.
    fun interrupt(): List<ScheduledEdit> = synchronized(pending) {
        val finals = (pending.values + listOfNotNull(active?.first))
            .filter { it.final && isCurrent(it) }.sortedBy { it.revision }
        cancel()
        finals
    }
    private fun overlaps(first: Int, second: Int) = first == second || first == LedTarget.All || second == LedTarget.All
    private fun intersects(first: LightingPatch, second: LightingPatch) =
        first.rgb != null && second.rgb != null || first.intensity != null && second.intensity != null || first.power != null && second.power != null
}

class OutboundPacer(
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    var intervalMillis: Long = 200
) {
    private val mutex = Mutex()
    private var lastSent = Long.MIN_VALUE / 2
    suspend fun <T> transmit(block: suspend () -> T): T = mutex.withLock {
        val wait = intervalMillis - (now() - lastSent)
        if (wait > 0) sleep(wait)
        currentCoroutineContext().ensureActive()
        lastSent = now()
        block()
    }
}

class TransactionIds(private val now: () -> Long) {
    private var next = 0
    private val lastUse = mutableMapOf<Int, Long>()
    fun allocate(): Int {
        repeat(256) {
            val id = next
            next = (next + 1) and 255
            val previous = lastUse[id]
            if (previous == null || now() - previous >= 6000) {
                lastUse[id] = now()
                return id
            }
        }
        error("Transaction ID window exhausted")
    }
}
