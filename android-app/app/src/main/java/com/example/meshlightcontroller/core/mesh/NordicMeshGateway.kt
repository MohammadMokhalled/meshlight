package com.example.meshlightcontroller.core.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.meshlightcontroller.core.control.OutboundPacer
import com.example.meshlightcontroller.core.control.TransactionIds
import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.core.protocol.MeshlightProtocol
import com.example.meshlightcontroller.data.SequenceGuard
import com.example.meshlightcontroller.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.android.mesh.*
import no.nordicsemi.android.mesh.logger.MeshLogger
import no.nordicsemi.android.mesh.provisionerstates.ProvisioningState
import no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode
import no.nordicsemi.android.mesh.transport.*
import no.nordicsemi.android.mesh.utils.*
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission", "RestrictedApi", "WrongConstant")
@Singleton
class NordicMeshGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val sequences: SequenceGuard
) : MeshGateway, MeshManagerCallbacks, MeshProvisioningStatusCallbacks, MeshStatusCallbacks {
    private val api = MeshManagerApi(context)
    private val mesh: MeshNetwork get() = requireNotNull(api.meshNetwork) { "Network is not loaded" }
    private var proxySource: Int? = null
    private val _network = MutableStateFlow<GatewayNetwork?>(null)
    override val network = _network.asStateFlow()
    private val _connection = MutableStateFlow(ConnectionStatus.IDLE)
    override val connection = _connection.asStateFlow()
    private val _nearby = MutableStateFlow<List<NearbyLight>>(emptyList())
    override val nearby = _nearby.asStateFlow()
    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 128)
    override val events = _events.asSharedFlow()
    private val loaded = CompletableDeferred<Unit>()
    private val pacer = OutboundPacer(SystemClock::elapsedRealtime)
    private val tids = TransactionIds(SystemClock::elapsedRealtime)
    private val transactionReadyAt = SystemClock.elapsedRealtime() + 6000
    private val requests = Mutex()
    private val links = Mutex()
    private var waiter: Waiter? = null
    private var provisioningResult: CompletableDeferred<GatewayNode>? = null
    private data class Outgoing(
        val bytes: ByteArray, val epoch: Long, val networkId: String,
        val source: Int, val ivIndex: Int, val sequence: Int, val owner: Deferred<*>?
    )
    private val tx = Channel<Outgoing>(128)
    private var linkEpoch = 0L
    private var activeTransmission: Deferred<Unit>? = null
    private val companies = mutableMapOf<Int, Int>()
    private var discovery: Job? = null
    private var reconnect: Job? = null
    private var foreground = false
    private var enabled = false
    private var changingSession = false
    private var proxyAddress: String? = null
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val bearer = MeshBleBearer(context, { mtu, data ->
        val epoch = linkEpoch
        scope.launch {
            if (epoch == linkEpoch) runCatching { api.handleNotifications(mtu, data) }.onFailure { log("Malformed mesh notification discarded") }
        }
    }, {
        val lostProxy = _connection.value == ConnectionStatus.CONNECTED
        invalidateLink()
        _connection.value = ConnectionStatus.DISCONNECTED
        waiter?.result?.completeExceptionally(IllegalStateException("Mesh proxy disconnected"))
        if (lostProxy && foreground && enabled && !changingSession && provisioningResult == null) startReconnect()
    })

    private data class Incoming(val source: Int, val message: MeshMessage? = null, val opcode: Int? = null, val company: Int? = null, val bytes: ByteArray? = null)
    private val heartbeat = ProxyHeartbeat(scope, { bearer.keepAlive() }, {
        log("GATT keep-alive failed; reconnecting proxy")
        withContext(NonCancellable) { bearer.closeLink() }
    })
    private data class Waiter(val accept: (Incoming) -> Boolean, val result: CompletableDeferred<Incoming>)

    init {
        MeshLogger.setLogHandler { _, _, _ -> }
        api.setMeshManagerCallbacks(this)
        api.setProvisioningStatusCallbacks(this)
        api.setMeshStatusCallbacks(this)
        scope.launch {
            for (packet in tx) {
                fun current() = packet.epoch == linkEpoch && packet.networkId == api.meshNetwork?.meshUUID &&
                    packet.owner?.isCancelled != true && bearer.isConnected
                if (!current()) continue
                val transmission = scope.async {
                    sequences.beforeTransmit(packet.networkId, packet.source, packet.ivIndex, packet.sequence)
                    if (!current()) return@async
                    bearer.send(packet.bytes) { mtu, data ->
                        if (current()) api.handleWriteCallbacks(mtu, data)
                    }
                }
                activeTransmission = transmission
                try { transmission.await() }
                catch (cancel: CancellationException) { currentCoroutineContext().ensureActive() }
                catch (error: Exception) {
                    if (packet.epoch == linkEpoch) {
                        waiter?.result?.completeExceptionally(error)
                        provisioningResult?.completeExceptionally(error)
                        _events.tryEmit(GatewayEvent.Error(safe(error)))
                    }
                } finally { if (activeTransmission === transmission) activeTransmission = null }
            }
        }
        api.loadMeshNetwork()
    }

    override suspend fun awaitLoaded() { loaded.await() }

    private fun trustedIdentity(): Boolean {
        val current = api.meshNetwork ?: return false
        return sequences.owner(current.meshUUID)?.equals(current.selectedProvisioner?.provisionerUuid, true) == true
    }

    private suspend fun claimCurrentIdentity() {
        sequences.claimOwner(mesh.meshUUID, requireNotNull(mesh.selectedProvisioner).provisionerUuid)
    }

    private suspend fun assignFreshController() {
        val current = mesh
        val provisioner = current.createProvisioner("MeshLight Controller",
            requireNotNull(current.nextAvailableUnicastAddressRange(64)) { "No free address range for a fresh controller" },
            requireNotNull(current.nextAvailableGroupAddressRange(256)) { "No free group range for a fresh controller" },
            requireNotNull(current.nextAvailableSceneAddressRange(16)) { "No free scene range for a fresh controller" })
        provisioner.assignProvisionerAddress(provisioner.allocatedUnicastRanges.first().lowAddress)
        check(current.addProvisioner(provisioner))
        current.selectProvisioner(provisioner)
        claimCurrentIdentity()
        recoverSequence(current)
        publish(current)
    }

    private fun publish(mesh: MeshNetwork) {
        val provisioners = mesh.provisioners.map { it.provisionerUuid.uppercase(Locale.ROOT) }.toSet()
        _network.value = GatewayNetwork(mesh.meshUUID, mesh.meshName,
            mesh.nodes.filter { it.uuid.uppercase(Locale.ROOT) !in provisioners }
                .map { node -> GatewayNode(node.uuid.uppercase(Locale.ROOT), node.unicastAddress, node.nodeName ?: "Meshlight",
                    node.elements.values.flatMap { it.meshModels.values }.filter { it.modelId == 0x1000 || it.modelId == 0x1002 || it.modelId !in 0..0xFFFF }.flatMap { it.subscribedAddresses }.toSet()) },
            mesh.groups.map { it.address to it.name })
    }

    private fun recoverSequence(mesh: MeshNetwork) {
        val local = mesh.selectedProvisioner?.let { mesh.getNode(it.provisionerUuid) } ?: return
        val floor = sequences.floor(mesh.meshUUID, local.unicastAddress, mesh.ivIndex.transmitIvIndex)
        local.sequenceNumber = maxOf(local.sequenceNumber, floor)
    }

    private fun repairControllerAddress() {
        if (MeshAddressing.repairControllerAddress(mesh)) {
            recoverSequence(mesh)
            publish(mesh)
            log("Controller address collision repaired; light setup can resume")
        }
    }

    override fun onNetworkLoaded(meshNetwork: MeshNetwork) {
        recoverSequence(meshNetwork)
        publish(meshNetwork)
        loaded.complete(Unit)
    }
    override fun onNetworkUpdated(meshNetwork: MeshNetwork) { publish(meshNetwork) }
    override fun onNetworkImported(meshNetwork: MeshNetwork) { recoverSequence(meshNetwork); publish(meshNetwork) }
    override fun onNetworkLoadFailed(error: String) { loaded.completeExceptionally(IllegalStateException("Unable to load mesh database")) }
    override fun onNetworkImportFailed(error: String) { _events.tryEmit(GatewayEvent.Error("Mesh database import failed")); importFailure = error }
    private var importFailure: String? = null
    override fun getMtu() = bearer.bearerMtu
    override fun sendProvisioningPdu(meshNode: UnprovisionedMeshNode, pdu: ByteArray) { enqueue(pdu) }
    override fun onMeshPduCreated(pdu: ByteArray) { enqueue(pdu) }

    private fun invalidateLink() {
        linkEpoch++
        heartbeat.stop()
        activeTransmission?.cancel()
        proxySource = null
    }

    private fun enqueue(pdu: ByteArray) {
        val current = api.meshNetwork ?: return
        val local = current.selectedProvisioner?.let { current.getNode(it.provisionerUuid) } ?: return
        val packet = Outgoing(pdu.copyOf(), linkEpoch, current.meshUUID, local.unicastAddress,
            current.ivIndex.transmitIvIndex, local.sequenceNumber, provisioningResult ?: waiter?.result)
        if (tx.trySend(packet).isFailure) {
            val error = IllegalStateException("Mesh transmit queue is full")
            waiter?.result?.completeExceptionally(error)
            provisioningResult?.completeExceptionally(error)
        }
    }

    override suspend fun createNetwork(name: String) {
        require(name.isNotBlank())
        awaitLoaded()
        disconnect()
        api.createMeshNetwork()
        val mesh = requireNotNull(api.meshNetwork)
        mesh.meshName = name.trim()
        val key = mesh.createAppKey()
        mesh.addAppKey(key)
        mesh.globalTtl = 7
        claimCurrentIdentity()
        publish(mesh)
    }

    override suspend fun renameNetwork(name: String) { mesh.meshName = name; publish(mesh) }
    override suspend fun renameNode(node: NodeInfo, name: String) { mesh.updateNodeName(node(node), name); publish(mesh) }

    override suspend fun selectNetwork(id: String) {
        disconnect()
        api.meshNetwork?.setLastSelected(false)
        withContext(Dispatchers.IO) {
            require(MeshlightNetworkCatalog.list(context).any { it.meshUUID.equals(id, true) })
            MeshlightNetworkCatalog.select(context, id)
        }
        _network.value = null
        api.loadMeshNetwork()
        withTimeout(10_000) { network.first { it?.id.equals(id, true) } }
        if (!trustedIdentity()) assignFreshController()
    }

    override suspend fun networks(): List<NetworkChoice> = withContext(Dispatchers.IO) {
        MeshlightNetworkCatalog.list(context).map { NetworkChoice(it.meshUUID, it.meshName) }
    }

    private fun permissionCheck() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            _connection.value = ConnectionStatus.PERMISSION_REQUIRED
            error("Bluetooth permissions are required")
        }
        if (adapter?.isEnabled != true) {
            _connection.value = ConnectionStatus.BLUETOOTH_OFF
            error("Bluetooth is switched off or unavailable")
        }
    }

    override fun setSessionEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (enabled && foreground) {
            if (bearer.isConnected && connection.value == ConnectionStatus.CONNECTED) heartbeat.start()
            else startReconnect()
        }
        if (!enabled) { heartbeat.stop(); reconnect?.cancel(); stopDiscovery() }
    }
    override fun setForeground(active: Boolean) {
        foreground = active
        if (active && enabled) {
            if (bearer.isConnected && connection.value == ConnectionStatus.CONNECTED) heartbeat.start()
            else startReconnect()
        }
        if (!active) {
            heartbeat.stop()
            reconnect?.cancel()
            stopDiscovery()
            scope.launch { disconnect() }
        }
    }
    private fun startReconnect() {
        if (reconnect?.isActive == true || bearer.isConnected || !foreground || !enabled || changingSession || network.value?.nodes.isNullOrEmpty()) return
        reconnect = scope.launch {
            for (attempt in 0 until 5) {
                if (!foreground || !enabled) return@launch
                if (attempt > 0) delay((1000L shl (attempt - 1)).coerceAtMost(8000))
                try { connectProxy(); return@launch }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    log("Proxy connection attempt ${attempt + 1} failed")
                    if (connection.value == ConnectionStatus.PERMISSION_REQUIRED || connection.value == ConnectionStatus.BLUETOOTH_OFF) return@launch
                }
            }
            _connection.value = ConnectionStatus.DISCONNECTED
        }
    }

    override fun discover() {
        reconnect?.cancel()
        stopDiscovery()
        discovery = scope.launch {
            try {
                permissionCheck()
                _nearby.value = emptyList()
                _connection.value = ConnectionStatus.SCANNING
                val scanner = requireNotNull(adapter?.bluetoothLeScanner)
                val callback = object : ScanCallback() {
                    override fun onScanResult(type: Int, result: ScanResult) {
                        val data = result.scanRecord?.getServiceData(ParcelUuid(MeshManagerApi.MESH_PROVISIONING_UUID)) ?: return
                        runCatching {
                            val uuid = api.getDeviceUuid(data).toString().uppercase(Locale.ROOT)
                            val light = NearbyLight(uuid, result.device.address, result.scanRecord?.deviceName, result.rssi)
                            _nearby.value = (_nearby.value.filterNot { it.uuid == uuid } + light).sortedByDescending { it.rssi }
                        }
                    }
                    override fun onScanFailed(errorCode: Int) { _events.tryEmit(GatewayEvent.Error("Bluetooth scan failed ($errorCode)")) }
                }
                try {
                    scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MeshManagerApi.MESH_PROVISIONING_UUID)).build()),
                        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                    delay(30_000)
                } finally { runCatching { scanner.stopScan(callback) } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { _events.tryEmit(GatewayEvent.Error(safe(error))) }
            finally {
                if (_connection.value == ConnectionStatus.SCANNING)
                    _connection.value = if (bearer.isConnected) ConnectionStatus.CONNECTED else ConnectionStatus.IDLE
            }
        }
    }
    override fun stopDiscovery() { discovery?.cancel(); discovery = null }

    override suspend fun connectProxy() = links.withLock {
        check(trustedIdentity()) { "Network import has not finished. Select the network again to create a fresh controller." }
        permissionCheck()
        if (bearer.isConnected && !bearer.provisioning && connection.value == ConnectionStatus.CONNECTED) return@withLock
        if (bearer.isConnected) { invalidateLink(); bearer.closeLink() }
        val mesh = requireNotNull(api.meshNetwork) { "Create or import a network first" }
        require(mesh.nodes.any { node -> mesh.provisioners.none { it.provisionerUuid.equals(node.uuid, true) } }) { "No provisioned lights in network" }
        try {
            repairControllerAddress()
            _connection.value = ConnectionStatus.SCANNING
            val device = scanProxy()
            _connection.value = ConnectionStatus.CONNECTING
            bearer.open(device)
            check(!bearer.provisioning)
            proxyAddress = device.address
            val filter = ProxyFilterType(ProxyFilterType.INCLUSION_LIST_FILTER)
            proxySource = status<ProxyConfigFilterStatus>(0, ProxyConfigSetFilterType(filter), anySource = true).src
            val source = requireNotNull(mesh.selectedProvisioner.provisionerAddress)
            status<ProxyConfigFilterStatus>(0, ProxyConfigAddAddressToFilter(listOf(AddressArray((source ushr 8).toByte(), source.toByte()))), anySource = true)
            _connection.value = ConnectionStatus.CONNECTED
            heartbeat.start()
            log("Proxy connected; controller reply filter configured")
        } catch (error: Exception) {
            invalidateLink()
            withContext(NonCancellable) { bearer.closeLink() }
            _connection.value = ConnectionStatus.DISCONNECTED
            throw error
        }
    }

    private suspend fun scanProxy(): BluetoothDevice = withTimeout(20_000) {
        suspendCancellableCoroutine { continuation ->
            val scanner = requireNotNull(adapter?.bluetoothLeScanner)
            val callback = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val data = result.scanRecord?.getServiceData(ParcelUuid(MeshManagerApi.MESH_PROXY_UUID)) ?: return
                    val matches = runCatching {
                        api.networkIdMatches(data) || mesh.nodes.any { api.nodeIdentityMatches(it, data) }
                    }.getOrDefault(false)
                    if (matches && continuation.isActive) {
                        scanner.stopScan(this)
                        continuation.resume(result.device)
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Proxy scan failed ($errorCode)"))
                }
            }
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MeshManagerApi.MESH_PROXY_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            continuation.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
        }
    }

    override suspend fun provision(light: NearbyLight): GatewayNode {
        check(trustedIdentity()) { "Select or create a network before adding lights" }
        permissionCheck()
        stopDiscovery()
        changingSession = true
        reconnect?.cancelAndJoin()
        return links.withLock {
            try {
                invalidateLink()
                bearer.closeLink()
                repairControllerAddress()
                _connection.value = ConnectionStatus.CONNECTING
                val result = CompletableDeferred<GatewayNode>()
                provisioningResult = result
                bearer.open(requireNotNull(adapter).getRemoteDevice(light.address))
                check(bearer.provisioning) { "Light is no longer unprovisioned" }
                api.identifyNode(UUID.fromString(light.uuid), 5)
                withTimeout(90_000) { result.await() }
            } finally {
                provisioningResult = null
                invalidateLink()
                withContext(NonCancellable) { bearer.closeLink() }
                changingSession = false
            }
        }
    }

    override fun onProvisioningStateChanged(meshNode: UnprovisionedMeshNode, state: ProvisioningState.States, data: ByteArray?) {
        if (state == ProvisioningState.States.PROVISIONING_CAPABILITIES) {
            runCatching {
                MeshAddressing.allocateProvisioningAddress(mesh, meshNode.numberOfElements)
                api.startProvisioning(meshNode)
            }.onFailure { provisioningResult?.completeExceptionally(it) }
        }
    }
    override fun onProvisioningFailed(meshNode: UnprovisionedMeshNode, state: ProvisioningState.States, data: ByteArray) {
        provisioningResult?.completeExceptionally(IllegalStateException("Provisioning failed at ${state.name}"))
    }
    override fun onProvisioningCompleted(meshNode: ProvisionedMeshNode, state: ProvisioningState.States, data: ByteArray) {
        provisioningResult?.complete(GatewayNode(meshNode.uuid.uppercase(Locale.ROOT), meshNode.unicastAddress, meshNode.nodeName ?: "Meshlight"))
        publish(mesh)
    }

    private fun node(info: NodeInfo) = requireNotNull(mesh.nodes.firstOrNull { it.uuid.equals(info.uuid, true) }) { "Light is not in this network" }
    private fun key(): ApplicationKey {
        val mesh = requireNotNull(api.meshNetwork)
        return mesh.appKeys.firstOrNull() ?: mesh.createAppKey().also { mesh.addAppKey(it) }
    }
    private fun vendorId(info: NodeInfo) = (info.companyId shl 16) or info.modelId

    override suspend fun configure(node: NodeInfo, progress: suspend (SetupStage) -> Unit): Capabilities {
        if (!bearer.isConnected) connectProxy()
        companies[node.address] = node.companyId
        val composition = status<ConfigCompositionDataStatus>(node.address, ConfigCompositionDataGet())
        val models = composition.elements.values.flatMap { it.meshModels.keys }.toSet()
        require(composition.elements.size == 1 && setOf(0x1000, 0x1002, vendorId(node)).all { it in models }) { "Unsupported light composition" }
        progress(SetupStage.COMPOSITION)
        checkStatus(status<ConfigAppKeyStatus>(node.address, ConfigAppKeyAdd(mesh.primaryNetworkKey, key())))
        progress(SetupStage.APP_KEY)
        for (model in listOf(0x1000, 0x1002, vendorId(node), 0x0002)) {
            checkStatus(status<ConfigModelAppStatus>(node.address, ConfigModelAppBind(node.address, model, key().keyIndex)) {
                it.elementAddress == node.address && it.modelIdentifier == model
            })
        }
        progress(SetupStage.BINDING)
        val incoming = vendorRequest(node, MeshlightProtocol.CapabilitiesGet, byteArrayOf(), MeshlightProtocol.CapabilitiesStatus, null)
        val capabilities = MeshlightProtocol.decodeCapabilities(requireNotNull(incoming.bytes))
        progress(SetupStage.CAPABILITIES)
        read(node.copy(capabilities = capabilities), LedTarget.All)
        return capabilities
    }

    override suspend fun capabilities(node: NodeInfo): Capabilities {
        companies[node.address] = node.companyId
        val response = vendorRequest(node, MeshlightProtocol.CapabilitiesGet, byteArrayOf(), MeshlightProtocol.CapabilitiesStatus, null)
        return MeshlightProtocol.decodeCapabilities(requireNotNull(response.bytes))
    }

    override suspend fun read(node: NodeInfo, target: Int): Observation {
        companies[node.address] = node.companyId
        val response = vendorRequest(node, MeshlightProtocol.StateGet, MeshlightProtocol.encodeGet(target), MeshlightProtocol.StateStatus, target)
        val decoded = MeshlightProtocol.decodeStatus(requireNotNull(response.bytes), System.currentTimeMillis())
        check(decoded.result == 0) { "Light rejected state read (${decoded.result})" }
        return requireNotNull(decoded.observation)
    }

    private suspend fun tid(): Int {
        val wait = transactionReadyAt - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        return tids.allocate()
    }

    override suspend fun write(node: NodeInfo, target: Int, patch: LightingPatch, acknowledged: Boolean): Observation? {
        companies[node.address] = node.companyId
        val parameters = MeshlightProtocol.encodeSet(target, tid(), patch)
        if (!acknowledged) {
            send(node.address, VendorModelMessageUnacked(key(), vendorId(node), node.companyId, MeshlightProtocol.StateSetUnacknowledged, parameters))
            return null
        }
        val response = vendorRequest(node, MeshlightProtocol.StateSet, parameters, MeshlightProtocol.StateStatus, target)
        val decoded = MeshlightProtocol.decodeStatus(requireNotNull(response.bytes), System.currentTimeMillis())
        check(decoded.result == 0) { "Light rejected edit (${decoded.result})" }
        val observation = requireNotNull(decoded.observation)
        return if (observation.matches(patch)) observation else read(node, target)
    }

    override suspend fun groupWrite(destination: Int, node: NodeInfo, patch: LightingPatch) {
        require(destination in 0xC000..0xFEFF)
        if (patch.rgb == null && (patch.power == null || patch.intensity == null)) standard(destination, patch)
        else send(destination, VendorModelMessageUnacked(key(), vendorId(node), node.companyId, MeshlightProtocol.StateSetUnacknowledged,
            MeshlightProtocol.encodeSet(LedTarget.All, tid(), patch)))
    }

    override suspend fun standard(destination: Int, patch: LightingPatch, fadeMillis: Long) {
        require(patch.rgb == null && !patch.isEmpty)
        val transition = MeshlightProtocol.transition(fadeMillis)
        patch.power?.let {
            send(destination, GenericOnOffSetUnacknowledged(key(), it, tid(), transition.steps, transition.resolution, 0))
        }
        patch.intensity?.let {
            send(destination, GenericLevelSetUnacknowledged(key(), transition.steps, transition.resolution, 0, MeshlightProtocol.intensityToLevel(it), tid()))
        }
    }

    override suspend fun renameRoom(address: Int, name: String) {
        val group = requireNotNull(mesh.getGroup(address))
        group.name = name
        mesh.updateGroup(group)
        publish(mesh)
    }

    override suspend fun createRoom(name: String): Int {
        val mesh = requireNotNull(api.meshNetwork)
        val room = mesh.createGroup(mesh.selectedProvisioner, name)
        require(room.address in 0xC000..0xFEFF)
        check(mesh.addGroup(room))
        publish(mesh)
        return room.address
    }

    override suspend fun roomMembership(node: NodeInfo, address: Int, add: Boolean) {
        for (model in listOf(0x1000, 0x1002, vendorId(node))) {
            val current = if (model in 0..0xFFFF) {
                status<ConfigSigModelSubscriptionList>(node.address, ConfigSigModelSubscriptionGet(node.address, model)) {
                    it.elementAddress == node.address && it.modelIdentifier == model
                }.also { checkStatus(it) }.subscriptionAddresses
            } else {
                status<ConfigVendorModelSubscriptionList>(node.address, ConfigVendorModelSubscriptionGet(node.address, model)) {
                    it.elementAddress == node.address && it.modelIdentifier == model
                }.also { checkStatus(it) }.subscriptionAddresses
            }
            if ((address in current) == add) continue
            require(!add || current.size < 4) { "Group subscription capacity reached" }
            val message: MeshMessage = if (add) ConfigModelSubscriptionAdd(node.address, address, model) else ConfigModelSubscriptionDelete(node.address, address, model)
            checkStatus(status<ConfigModelSubscriptionStatus>(node.address, message) {
                it.elementAddress == node.address && it.modelIdentifier == model && it.subscriptionAddress == address
            })
        }
    }

    override suspend fun deleteRoom(address: Int) {
        val mesh = this.mesh
        mesh.getGroup(address)?.let { mesh.removeGroup(it) }
        publish(mesh)
    }

    override suspend fun identify(node: NodeInfo) { send(node.address, MeshlightHealthAttention(key(), 5)) }

    override suspend fun settings(node: NodeInfo): NodeSettings {
        val ttl = status<ConfigDefaultTtlStatus>(node.address, ConfigDefaultTtlGet()).parameters[0].toInt() and 255
        val relay = status<ConfigRelayStatus>(node.address, ConfigRelayGet()).parameters[0].toInt() == 1
        val proxy = status<ConfigGattProxyStatus>(node.address, ConfigGattProxyGet()).parameters[0].toInt() == 1
        return NodeSettings(ttl, relay, proxy, proxySource == null || proxySource == 0 || proxySource == node.address)
    }

    override suspend fun settings(node: NodeInfo, ttl: Int, relay: Boolean, proxy: Boolean) {
        val oldRelay = status<ConfigRelayStatus>(node.address, ConfigRelayGet()).parameters
        val retransmit = oldRelay.getOrNull(1)?.toInt()?.and(255) ?: 0
        status<ConfigDefaultTtlStatus>(node.address, ConfigDefaultTtlSet(ttl))
        status<ConfigRelayStatus>(node.address, ConfigRelaySet(if (relay) 1 else 0, retransmit and 7, retransmit ushr 3))
        status<ConfigGattProxyStatus>(node.address, ConfigGattProxySet(if (proxy) 1 else 0))
    }

    override suspend fun reset(node: NodeInfo) {
        status<ConfigNodeResetStatus>(node.address, ConfigNodeReset())
        publish(mesh)
    }
    override suspend fun forget(node: NodeInfo) { mesh.deleteNode(node(node)); publish(mesh) }
    override suspend fun exportCdb(): String = withContext(Dispatchers.IO) { requireNotNull(api.exportMeshNetwork()) }

    override suspend fun importCdb(json: String) {
        disconnect()
        changingSession = true
        try {
            importFailure = null
            withContext(Dispatchers.IO) { api.importMeshNetworkJson(json) }
            check(importFailure == null) { "Mesh database import failed" }
            assignFreshController()
        } finally { changingSession = false }
    }

    override suspend fun disconnect() {
        changingSession = true
        reconnect?.cancelAndJoin()
        try {
            stopDiscovery()
            invalidateLink()
            waiter?.result?.completeExceptionally(IllegalStateException("Mesh session ended"))
            withContext(NonCancellable) { bearer.closeLink() }
            proxyAddress = null
            _connection.value = ConnectionStatus.IDLE
        } finally { changingSession = false }
    }

    private suspend fun send(destination: Int, message: MeshMessage) {
        check(trustedIdentity()) { "A fresh local controller identity is required" }
        check(bearer.isConnected && !bearer.provisioning && !changingSession) { "Connect to a mesh proxy first" }
        pacer.transmit {
            recoverSequence(mesh)
            api.createMeshPdu(destination, message)
        }
    }

    private suspend fun exchange(destination: Int, message: MeshMessage, accept: (Incoming) -> Boolean): Incoming = requests.withLock {
        var last: Exception? = null
        repeat(3) {
            currentCoroutineContext().ensureActive()
            val expected = Waiter(accept, CompletableDeferred())
            waiter = expected
            try {
                send(destination, message)
                return@withLock withTimeout(5000) { expected.result.await() }
            } catch (cancel: TimeoutCancellationException) { last = IllegalStateException("Light did not respond") }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { last = error; if (!bearer.isConnected) throw error }
            finally { expected.result.cancel(); if (waiter === expected) waiter = null }
        }
        throw requireNotNull(last)
    }

    private suspend inline fun <reified T : MeshMessage> status(
        destination: Int, message: MeshMessage, anySource: Boolean = false, crossinline accept: (T) -> Boolean = { true }
    ): T = exchange(destination, message) {
        (anySource || it.source == destination) && it.message is T && accept(it.message)
    }.message as T

    private suspend fun vendorRequest(node: NodeInfo, opcode: Int, bytes: ByteArray, response: Int, target: Int?): Incoming =
        exchange(node.address, VendorModelMessageAcked(key(), vendorId(node), node.companyId, opcode, bytes)) {
            it.source == node.address && it.opcode == response && it.company == node.companyId &&
                (target == null || it.bytes?.let { data -> data.size >= 2 && ((data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8)) == target } == true)
        }

    private fun checkStatus(message: ConfigStatusMessage) { check(message.statusCode == 0) { "Mesh configuration rejected: ${message.statusCodeName}" } }
    private fun received(incoming: Incoming) {
        val pending = waiter
        if (pending?.accept?.invoke(incoming) == true) pending.result.complete(incoming)
    }
    private fun vendor(source: Int, payload: ByteArray) {
        if (payload.size < 3) return
        val opcode = payload[0].toInt() and 255
        val company = (payload[1].toInt() and 255) or ((payload[2].toInt() and 255) shl 8)
        if (opcode !in listOf(MeshlightProtocol.CapabilitiesStatus, MeshlightProtocol.StateStatus)) return
        val parameters = payload.copyOfRange(3, payload.size)
        received(Incoming(source, opcode = opcode, company = company, bytes = parameters))
        if (opcode == MeshlightProtocol.StateStatus && companies[source] == company) {
            runCatching { MeshlightProtocol.decodeStatus(parameters, System.currentTimeMillis()) }.onSuccess { status ->
                status.observation?.let { _events.tryEmit(GatewayEvent.Report(source, status.target, it)) }
            }
        }
    }
    override fun onMeshMessageReceived(src: Int, meshMessage: MeshMessage) {
        if (meshMessage is VendorModelMessageStatus) vendor(src, meshMessage.accessPayload)
        else {
            received(Incoming(src, message = meshMessage))
            runCatching {
                val transition = when (meshMessage) {
                    is GenericOnOffStatus -> MeshlightProtocol.decodeOnOffStatus(meshMessage.parameters).let {
                        if (it.target == null) null else TransitionInfo(targetPower = it.target, remainingMillis = it.remainingMillis, receivedAt = System.currentTimeMillis())
                    }
                    is GenericLevelStatus -> MeshlightProtocol.decodeLevelStatus(meshMessage.parameters).let {
                        if (it.target == null) null else TransitionInfo(targetIntensity = MeshlightProtocol.levelToIntensity(it.target), remainingMillis = it.remainingMillis, receivedAt = System.currentTimeMillis())
                    }
                    else -> return
                }
                _events.tryEmit(GatewayEvent.Transition(src, transition))
            }.onFailure { log("Malformed standard status discarded") }
        }
    }
    override fun onUnknownPduReceived(src: Int, accessPayload: ByteArray) { vendor(src, accessPayload) }
    override fun onTransactionFailed(dst: Int, hasIncompleteTimerExpired: Boolean) {
        waiter?.result?.completeExceptionally(IllegalStateException("Mesh transaction failed"))
    }
    override fun onMeshMessageProcessed(dst: Int, meshMessage: MeshMessage) = Unit
    override fun onBlockAcknowledgementProcessed(dst: Int, message: ControlMessage) = Unit
    override fun onBlockAcknowledgementReceived(src: Int, message: ControlMessage) = Unit
    override fun onHeartbeatMessageReceived(src: Int, message: ControlMessage) = Unit
    override fun onMessageDecryptionFailed(meshLayer: String, errorMessage: String) { log("Mesh packet decryption failed") }
    private fun log(text: String) { _events.tryEmit(GatewayEvent.Log(text)) }
    private fun safe(error: Throwable) = error.message?.replace(Regex("(?i)[0-9a-f]{32,}"), "[redacted]") ?: "Bluetooth operation failed"
}
