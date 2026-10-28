package com.example.meshlightcontroller.core.mesh

import kotlinx.coroutines.*

internal class ProxyHeartbeat(
    private val scope: CoroutineScope,
    private val probe: suspend () -> Unit,
    private val failed: suspend (Exception) -> Unit
) {
    private var job: Job? = null

    fun start() {
        stop()
        job = scope.launch {
            while (isActive) {
                delay(2000)
                try { probe() }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { failed(error); return@launch }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
