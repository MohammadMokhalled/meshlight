package com.example.meshlightcontroller.data

import com.example.meshlightcontroller.core.control.*
import com.example.meshlightcontroller.core.mesh.*
import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.di.ApplicationScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class)
@Singleton
class LightingRepository @Inject constructor(
    internal val real: MeshGateway,
    internal val demo: DemoMeshGateway,
    internal val store: InstallationStore,
    internal val preferences: AppPreferences,
    @ApplicationScope internal val scope: CoroutineScope
) {
    internal val mutableState = MutableStateFlow(ControllerState(loading = true))
    val state = mutableState.asStateFlow()
    val appearance = preferences.flow.stateIn(scope, SharingStarted.Eagerly, UiPreferences())
    internal var gateway: MeshGateway = real
    internal val liveEdits = mutableMapOf<EditKey, LightingPatch>()
    private val listeners = mutableListOf<Job>()
    private var visibleRead: Job? = null
    internal var backgroundRead: Job? = null
    private var operationJob: Job? = null
    private var operationGeneration = 0L
    private var foreground = false
    private var binding = false
    private val reconnectEdits = linkedMapOf<EditKey, ScheduledEdit>()

    internal val scheduler = EditScheduler(scope, { edit ->
        check(!state.value.readOnly && state.value.connection == ConnectionStatus.CONNECTED) { "Light edit was not sent" }
        if (edit.key.nodeUuid.startsWith("room:")) { sendRoomEdit(edit); return@EditScheduler }
        val node = node(edit.key.nodeUuid)
        gateway.write(node, edit.key.target, edit.patch, edit.final)?.let {
            record(node.uuid, edit.key.target, it)
            check(it.matches(edit.patch)) { "Light reported a different value; refresh and try again" }
        }
    }, { edit, failure ->
        if (failure != null && edit.final && foreground &&
            gateway.connection.value in setOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.CONNECTING, ConnectionStatus.SCANNING)) {
            retainEdit(edit)
            return@EditScheduler
        }
        if (edit.key.nodeUuid.startsWith("room:")) { finishRoomEdit(edit, failure); return@EditScheduler }
        if (failure == null && edit.final) {
            liveEdits.remove(edit.key)
            updateNode(edit.key.nodeUuid) { node ->
                val remaining = node.drafts[edit.key.target]?.without(edit.patch)
                node.copy(drafts = if (remaining == null || remaining.isEmpty) node.drafts - edit.key.target else node.drafts + (edit.key.target to remaining), lastError = null)
            }
            if (edit.key.target != LedTarget.All) backgroundRead = scope.launch { runCatching { gateway.read(node(edit.key.nodeUuid), LedTarget.All) } }
        } else if (failure != null) {
            liveEdits.remove(edit.key)
            updateNode(edit.key.nodeUuid) { it.copy(lastError = safe(failure)) }
            message(failure)
        }
        mutableState.update { it.copy(pendingTargets = it.pendingTargets - token(edit.key)) }
    })

    init {
        scope.launch {
            try { real.awaitLoaded() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { message(error); return@launch }
            preferences.flow.map { Triple(it.demo, it.networkId, it.transferredNetworks) }.distinctUntilChanged().collect { (demoMode, id, transferred) ->
                try { bind(demoMode, id, id in transferred) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { message(error) }
            }
        }
        scope.launch {
            state.map { it.installation }.filterNotNull().distinctUntilChanged().debounce(800).collect { value ->
                try { store.write(value) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { message(IllegalStateException("Unable to save this home on the phone", error)) }
            }
        }
    }

    private suspend fun bind(demoMode: Boolean, id: String, readOnly: Boolean) {
        if (binding) return
        binding = true
        mutableState.update { it.copy(loading = true, connection = ConnectionStatus.IDLE) }
        try {
            listeners.forEach(Job::cancel)
            listeners.clear()
            cancelScheduledEdits()
            backgroundRead?.cancel()
            visibleRead?.cancel()
            gateway.setSessionEnabled(false)
            gateway.disconnect()
            liveEdits.clear()
            gateway = if (demoMode) demo else real
            if (!demoMode && id.isNotBlank()) real.selectNetwork(id)
            val saved = if (id.isNotBlank()) store.read(id) else null
            if (demoMode && saved != null) demo.restore(saved)
            val snapshot = gateway.network.value
            val installation = if (demoMode) saved ?: demo.installation()
                else if (id.isNotBlank() && snapshot != null) saved ?: Installation(networkId = snapshot.id, name = snapshot.name)
                else null
            mutableState.value = ControllerState(installation, gateway.connection.value, demo = demoMode, readOnly = readOnly,
                networks = if (demoMode) emptyList() else real.networks())
            if (installation != null && snapshot != null) synchronize(snapshot)
            gateway.setForeground(foreground)
            gateway.setSessionEnabled(installation != null && !readOnly)
            listeners += scope.launch {
                gateway.network.filterNotNull().collect { if (state.value.installation?.networkId == it.id) synchronize(it) }
            }
            listeners += scope.launch {
                gateway.nearby.collect { values -> mutableState.update { it.copy(nearby = values) } }
            }
            listeners += scope.launch {
                gateway.connection.collect { connection ->
                    mutableState.update { it.copy(connection = connection) }
                    if (connection != ConnectionStatus.CONNECTED) {
                        if (foreground && !state.value.readOnly && connection in setOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.CONNECTING, ConnectionStatus.SCANNING)) {
                            scheduler.interrupt().forEach(::retainEdit)
                        } else cancelScheduledEdits()
                        liveEdits.clear()
                        mutableState.update { it.copy(pendingTargets = emptySet()) }
                    } else {
                        resumeEdits()
                        if (state.value.operation?.running != true && state.value.pendingTargets.isEmpty()) refreshBackground()
                    }
                }
            }
            listeners += scope.launch {
                gateway.events.collect { event ->
                    when (event) {
                        is GatewayEvent.Report -> state.value.installation?.nodes?.firstOrNull { it.address == event.source }?.let { record(it.uuid, event.target, event.observation) }
                        is GatewayEvent.Transition -> state.value.installation?.nodes?.firstOrNull { it.address == event.source }?.let { node -> updateNode(node.uuid) { it.copy(transition = event.value) } }
                        is GatewayEvent.Error -> message(IllegalStateException(event.text))
                        is GatewayEvent.Log -> mutableState.update { it.copy(diagnostics = (listOf(event.text) + it.diagnostics).take(100)) }
                    }
                }
            }
        } finally { binding = false }
    }

    private fun synchronize(snapshot: GatewayNetwork) {
        updateInstallation { current ->
            val nodes = snapshot.nodes.map { sdk ->
                current.nodes.firstOrNull { it.uuid.equals(sdk.uuid, true) }?.copy(address = sdk.address)
                    ?: NodeInfo(sdk.uuid.uppercase(Locale.ROOT), sdk.address, sdk.name, appearance.value.companyId, appearance.value.modelId)
            }
            val rooms = snapshot.rooms.map { (address, name) ->
                current.rooms.firstOrNull { it.address == address }
                    ?: RoomInfo(address, name, snapshot.nodes.filter { address in it.subscriptions }.map { it.uuid }.toSet())
            }
            current.copy(nodes = nodes, rooms = rooms)
        }
    }

    fun setForeground(active: Boolean) {
        foreground = active
        gateway.setForeground(active && !state.value.readOnly)
        if (!active) {
            cancelScheduledEdits()
            cancelVisible()
            backgroundRead?.cancel()
            cancelOperation()
            scope.launch { saveNow() }
        }
    }

    fun createNetwork(name: String) = startOperation(OperationKind.CONFIGURE) {
        require(name.isNotBlank())
        real.createNetwork(name.trim())
        val network = requireNotNull(real.network.value)
        store.write(Installation(networkId = network.id, name = name.trim()))
        preferences.session(false, network.id)
    }

    fun enterDemo() { scope.launch { preferences.session(true, "demo") } }
    fun leaveDemo() { scope.launch { preferences.session(false, preferences.flow.first().lastRealNetwork) } }
    fun switchNetwork(id: String) { cancelOperation(); scope.launch { saveNow(); preferences.session(false, id) } }
    fun discover() { gateway.discover() }
    fun stopDiscovery() { gateway.stopDiscovery() }
    fun connect() = startOperation(OperationKind.CONFIGURE) { gateway.connectProxy(); refreshBackground() }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    fun dismissOperation() { if (state.value.operation?.running != true) mutableState.update { it.copy(operation = null) } }

    fun addLight(light: NearbyLight, name: String, replace: Boolean = false) = startOperation(OperationKind.PROVISION, 7) {
        checkEditable()
        val previous = installation().nodes.firstOrNull { it.uuid.equals(light.uuid, true) }
        require(previous == null || replace) { "Remove the previous light entry before provisioning it again" }
        if (previous != null) { gateway.forget(previous); removeNode(previous.uuid, clearedSubscriptions = true) }
        val provisioned = gateway.provision(light)
        removeNode(provisioned.uuid, clearedSubscriptions = true)
        updateInstallation { current ->
            current.copy(nodes = current.nodes.filterNot { it.uuid == provisioned.uuid } +
                NodeInfo(provisioned.uuid, provisioned.address, name.ifBlank { provisioned.name }, appearance.value.companyId, appearance.value.modelId))
        }
        saveNow()
        advance()
        configure(provisioned.uuid)
    }

    fun finishSetup(uuid: String) = startOperation(OperationKind.CONFIGURE, 6) { checkEditable(); configure(uuid) }

    private suspend fun configure(uuid: String) {
        val before = node(uuid)
        try {
            val capabilities = gateway.configure(before) { stage ->
                updateNode(uuid) { it.copy(stage = stage, lastError = null) }
                saveNow()
                advance()
            }
            if (before.capabilities != null && before.capabilities.ledCount != capabilities.ledCount) invalidate(uuid)
            updateNode(uuid) { it.copy(capabilities = capabilities, stage = SetupStage.READY, lastError = null) }
            saveNow()
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            updateNode(uuid) { it.copy(lastError = safe(error), stage = if (safe(error).contains("Unsupported", true)) SetupStage.UNSUPPORTED else it.stage) }
            saveNow()
            throw error
        }
    }

    fun edit(uuid: String, targets: Set<Int>, patch: LightingPatch, final: Boolean = true, fadeMillis: Long = 0) {
        if (patch.isEmpty || targets.isEmpty()) return
        if (state.value.readOnly) { message(IllegalStateException("This network has been transferred")); return }
        if (state.value.operation?.kind in setOf(OperationKind.APPLY, OperationKind.REFRESH, OperationKind.CAPTURE)) cancelOperation()
        backgroundRead?.cancel()
        val node = node(uuid)
        val capabilities = node.capabilities ?: return
        require(targets.all { it == LedTarget.All || it in 0 until capabilities.ledCount })
        require(patch.rgb == null || capabilities.supportsRgb)
        require(patch.intensity == null || (capabilities.supportsIntensity && patch.intensity <= capabilities.maxIntensity))
        require(patch.power == null || capabilities.supportsPower)
        updateNode(uuid) { current ->
            var drafts = current.drafts
            targets.forEach { target ->
                if (target == LedTarget.All) drafts = drafts.mapValues { (key, old) -> if (key == LedTarget.All) old else old.without(patch) }.filterValues { !it.isEmpty }
                drafts = drafts + (target to (drafts[target]?.merge(patch) ?: patch))
            }
            current.copy(drafts = drafts, lastError = null,
                aggregate = current.aggregate?.copy(receivedAt = 0),
                leds = if (LedTarget.All in targets) current.leds.mapValues { it.value.copy(receivedAt = 0) } else current.leds)
        }
        if (final && patch.rgb != null) updateInstallation { it.copy(recentColors = (listOf(patch.rgb) + it.recentColors).distinct().take(8)) }
        if (state.value.connection != ConnectionStatus.CONNECTED) {
            forgetReconnectEdits(uuid)
            return
        }
        if (fadeMillis > 0 && targets == setOf(LedTarget.All) && patch.rgb == null) {
            startOperation(OperationKind.APPLY) {
                gateway.standard(node.address, patch, fadeMillis)
                updateNode(uuid) { it.copy(transition = TransitionInfo(patch.power, patch.intensity, fadeMillis, System.currentTimeMillis())) }
                delay(fadeMillis + 250)
                val observed = gateway.read(node, LedTarget.All)
                record(uuid, LedTarget.All, observed)
                check(observed.matches(patch)) { "Fade could not be verified" }
                updateNode(uuid) { current ->
                    val remaining = current.drafts[LedTarget.All]?.without(patch)
                    current.copy(drafts = if (remaining == null || remaining.isEmpty) current.drafts - LedTarget.All else current.drafts + (LedTarget.All to remaining))
                }
            }
        } else targets.forEach { target ->
            val key = EditKey(uuid, target)
            mutableState.update { it.copy(pendingTargets = it.pendingTargets + token(key)) }
            val live = liveEdits[key]?.merge(patch) ?: patch
            liveEdits[key] = live
            scheduler.enqueue(key, if (final) live else patch, final)
        }
    }

    fun discardDrafts(uuid: String) {
        cancelScheduledEdits()
        liveEdits.clear()
        mutableState.update { it.copy(pendingTargets = emptySet()) }
        updateNode(uuid) { it.copy(drafts = emptyMap()) }
    }

    fun applyDrafts(uuid: String) = startOperation(OperationKind.APPLY, node(uuid).drafts.size) {
        checkEditable()
        if (state.value.connection != ConnectionStatus.CONNECTED) gateway.connectProxy()
        node(uuid).drafts.entries.sortedBy { if (it.key == LedTarget.All) -1 else it.key }.forEach { (target, patch) ->
            direct(PlannedEdit(uuid, target, patch))
            advance()
        }
        gateway.read(node(uuid), LedTarget.All)
    }

    internal suspend fun direct(edit: PlannedEdit) {
        currentCoroutineContext().ensureActive()
        scheduler.supersede(EditKey(edit.nodeUuid, edit.target))
        updateNode(edit.nodeUuid) { it.copy(drafts = it.drafts + (edit.target to (it.drafts[edit.target]?.merge(edit.patch) ?: edit.patch))) }
        val observed = requireNotNull(gateway.write(node(edit.nodeUuid), edit.target, edit.patch, true))
        record(edit.nodeUuid, edit.target, observed)
        check(observed.matches(edit.patch)) { "Light reported a different value" }
        updateNode(edit.nodeUuid) { current ->
            val remaining = current.drafts[edit.target]?.confirmedBy(edit.patch)
            current.copy(drafts = if (remaining == null || remaining.isEmpty) current.drafts - edit.target else current.drafts + (edit.target to remaining), lastError = null)
        }
    }

    internal suspend fun batch(edits: List<PlannedEdit>) {
        // Keep every unsent target as an explicit draft, including targets skipped after a timeout.
        edits.forEach { edit ->
            updateNode(edit.nodeUuid) { current ->
                current.copy(drafts = current.drafts + (edit.target to (current.drafts[edit.target]?.merge(edit.patch) ?: edit.patch)))
            }
        }
        val unavailable = mutableSetOf<String>()
        for (edit in edits) {
            currentCoroutineContext().ensureActive()
            try {
                check(edit.nodeUuid !in unavailable) { "Skipped after light became unreachable" }
                direct(edit)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { unavailable += edit.nodeUuid; failure("${edit.nodeUuid}/${edit.target}", error) }
            advance()
        }
        edits.map { it.nodeUuid }.distinct().filterNot { it in unavailable }.forEach { uuid ->
            runCatching { gateway.read(node(uuid), LedTarget.All) }
        }
    }

    fun refreshNode(uuid: String) {
        backgroundRead?.cancel()
        backgroundRead = scope.launch { try { refreshCapabilities(uuid); gateway.read(node(uuid), LedTarget.All) } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { message(error) } }
    }

    private fun refreshBackground() {
        backgroundRead?.cancel()
        backgroundRead = scope.launch {
            installation().nodes.filter { it.stage == SetupStage.READY }.forEach { node ->
                try { refreshCapabilities(node.uuid); gateway.read(node(node.uuid), LedTarget.All) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { updateNode(node.uuid) { it.copy(lastError = safe(error)) } }
            }
        }
    }

    private suspend fun refreshCapabilities(uuid: String) {
        val before = node(uuid)
        val capabilities = gateway.capabilities(before)
        if (before.capabilities != capabilities) {
            if (before.capabilities != null) invalidate(uuid)
            updateNode(uuid) { current ->
                current.copy(capabilities = capabilities, stage = SetupStage.READY,
                    leds = current.leds.filterKeys { it in 0 until capabilities.ledCount },
                    drafts = current.drafts.filterKeys { it == LedTarget.All || it in 0 until capabilities.ledCount })
            }
            saveNow()
        }
    }

    fun visibleLeds(uuid: String, indices: List<Int>) {
        visibleRead?.cancel()
        if (state.value.connection != ConnectionStatus.CONNECTED) return
        visibleRead = scope.launch {
            for (index in indices.distinct()) {
                val value = node(uuid)
                if (index !in 0 until (value.capabilities?.ledCount ?: 0)) continue
                if (System.currentTimeMillis() - (value.leds[index]?.receivedAt ?: 0) < 30_000) continue
                try { gateway.read(value, index) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { updateNode(uuid) { it.copy(lastError = safe(error)) }; break }
            }
        }
    }
    fun cancelVisible() { visibleRead?.cancel() }

    fun refreshStrip(uuid: String) = startOperation(OperationKind.REFRESH, node(uuid).capabilities?.ledCount ?: 0) {
        for (index in 0 until requireNotNull(node(uuid).capabilities).ledCount) {
            gateway.read(node(uuid), index)
            advance()
        }
    }

    fun renameNode(uuid: String, name: String) {
        require(name.isNotBlank())
        updateNode(uuid) { it.copy(name = name.trim()) }
        scope.launch { gateway.renameNode(node(uuid), name.trim()); saveNow() }
    }
    fun renameNetwork(name: String) = startOperation(OperationKind.CONFIGURE) {
        checkEditable()
        require(name.isNotBlank())
        gateway.renameNetwork(name.trim())
        updateInstallation { it.copy(name = name.trim()) }
        saveNow()
    }
    fun favorite(uuid: String) { updateNode(uuid) { it.copy(favorite = !it.favorite) } }
    fun reverse(uuid: String) { updateNode(uuid) { it.copy(reverseDisplay = !it.reverseDisplay) } }
    fun favoriteColor(rgb: Rgb) { updateInstallation { it.copy(favoriteColors = if (rgb in it.favoriteColors) it.favoriteColors - rgb else it.favoriteColors + rgb) } }
    fun identify(uuid: String) = startOperation(OperationKind.CONFIGURE) { gateway.identify(node(uuid)) }
    suspend fun readSettings(uuid: String) = gateway.settings(node(uuid))
    fun writeSettings(uuid: String, ttl: Int, relay: Boolean, proxy: Boolean) = startOperation(OperationKind.CONFIGURE) {
        checkEditable(); gateway.settings(node(uuid)); gateway.settings(node(uuid), ttl, relay, proxy)
    }
    fun reset(uuid: String) = startOperation(OperationKind.RESET) {
        checkEditable(); gateway.reset(node(uuid)); removeNode(uuid, true); saveNow()
    }
    fun forget(uuid: String) = startOperation(OperationKind.RESET) {
        checkEditable(); gateway.forget(node(uuid)); removeNode(uuid, false); saveNow()
    }

    internal fun removeNode(uuid: String, clearedSubscriptions: Boolean) {
        invalidate(uuid)
        updateInstallation { value -> value.copy(nodes = value.nodes.filterNot { it.uuid == uuid },
            rooms = value.rooms.map { room ->
                room.copy(members = room.members - uuid, confirmed = room.confirmed - uuid,
                    pendingRemoval = if (clearedSubscriptions) room.pendingRemoval - uuid else if (uuid in room.members || uuid in room.confirmed) room.pendingRemoval + uuid else room.pendingRemoval)
            }) }
    }
    internal fun invalidate(uuid: String) {
        updateInstallation { value -> value.copy(
            zones = value.zones.map { if (it.leds.any { ref -> ref.nodeUuid == uuid }) it.copy(invalidated = true) else it },
            presets = value.presets.map { if (it.edits.any { edit -> edit.ref.nodeUuid == uuid }) it.copy(invalidated = true) else it }) }
    }

    internal fun record(uuid: String, target: Int, observation: Observation) {
        updateNode(uuid) { current ->
            if (target == LedTarget.All) current.copy(aggregate = observation, lastError = null, transition = null)
            else current.copy(leds = current.leds + (target to observation), lastError = null)
        }
    }
    internal fun installation() = requireNotNull(state.value.installation) { "Create or import a network first" }
    internal fun node(uuid: String) = requireNotNull(installation().nodes.firstOrNull { it.uuid == uuid }) { "Light no longer exists" }
    internal fun updateInstallation(transform: (Installation) -> Installation) {
        mutableState.update { current -> current.installation?.let { current.copy(installation = transform(it)) } ?: current }
    }
    internal fun updateNode(uuid: String, transform: (NodeInfo) -> NodeInfo) {
        updateInstallation { it.copy(nodes = it.nodes.map { node -> if (node.uuid == uuid) transform(node) else node }) }
    }
    internal suspend fun saveNow() { state.value.installation?.let { store.write(it) } }
    private fun retainEdit(edit: ScheduledEdit) {
        if ((reconnectEdits[edit.key]?.revision ?: Long.MIN_VALUE) < edit.revision) reconnectEdits[edit.key] = edit
    }
    private fun resumeEdits() {
        val retained = reconnectEdits.values.sortedBy { it.revision }
        reconnectEdits.clear()
        retained.forEach { edit ->
            val installation = state.value.installation ?: return@forEach
            val node = installation.nodes.firstOrNull { it.uuid == edit.key.nodeUuid }
            val room = edit.key.nodeUuid.removePrefix("room:").toIntOrNull()
            val valid = if (edit.key.nodeUuid.startsWith("room:")) installation.rooms.any { it.address == room }
                else node != null && node.stage == SetupStage.READY &&
                    (edit.key.target == LedTarget.All || edit.key.target in 0 until (node.capabilities?.ledCount ?: 0))
            if (valid) {
                liveEdits[edit.key] = edit.patch
                mutableState.update { it.copy(pendingTargets = it.pendingTargets + token(edit.key)) }
                scheduler.enqueue(edit.key, edit.patch, true)
            }
        }
    }
    internal fun forgetReconnectEdits(uuid: String) { reconnectEdits.keys.removeAll { it.nodeUuid == uuid } }
    private fun cancelScheduledEdits() { reconnectEdits.clear(); scheduler.cancel() }
    internal fun checkEditable() { check(!state.value.readOnly) { "This network has been transferred" } }
    internal fun token(key: EditKey) = "${key.nodeUuid}/${key.target}"
    internal fun safe(error: Throwable) = error.message?.replace(Regex("(?i)[0-9a-f]{32,}"), "[redacted]") ?: "Operation failed"
    internal fun message(error: Throwable) { mutableState.update { it.copy(error = safe(error), loading = false, diagnostics = (listOf(safe(error)) + it.diagnostics).take(100)) } }

    internal fun startOperation(kind: OperationKind, total: Int = 1, block: suspend LightingRepository.() -> Unit): Job {
        val previous = operationJob
        previous?.cancel()
        cancelScheduledEdits()
        liveEdits.clear()
        mutableState.update { it.copy(pendingTargets = emptySet()) }
        backgroundRead?.cancel()
        visibleRead?.cancel()
        val generation = ++operationGeneration
        mutableState.update { it.copy(operation = OperationProgress(kind, total = total.coerceAtLeast(1)), error = null) }
        return scope.launch {
            try {
                previous?.join()
                currentCoroutineContext().ensureActive()
                saveNow()
                block()
                if (generation == operationGeneration) mutableState.update { current -> current.copy(operation = current.operation?.copy(running = false)) }
            } catch (cancel: CancellationException) {
                if (generation == operationGeneration) mutableState.update { it.copy(operation = it.operation?.copy(running = false, cancelled = true)) }
            } catch (error: Exception) {
                if (generation == operationGeneration) {
                    failure("operation", error)
                    mutableState.update { it.copy(operation = it.operation?.copy(running = false)) }
                    message(error)
                }
            }
        }.also { operationJob = it }
    }
    internal fun advance() { mutableState.update { state -> state.copy(operation = state.operation?.let { it.copy(completed = (it.completed + 1).coerceAtMost(it.total)) }) } }
    internal fun failure(target: String, error: Throwable) {
        mutableState.update { it.copy(operation = it.operation?.let { operation -> operation.copy(failures = operation.failures + (target to safe(error))) }) }
    }
    fun cancelOperation() {
        cancelScheduledEdits()
        liveEdits.clear()
        mutableState.update { it.copy(pendingTargets = emptySet()) }
        operationJob?.cancel()
        operationGeneration++
        mutableState.update { it.copy(operation = it.operation?.takeIf { progress -> progress.running }?.copy(running = false, cancelled = true) ?: it.operation) }
    }
}
