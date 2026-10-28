package com.example.meshlightcontroller.core.mesh

import com.example.meshlightcontroller.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DemoMeshGateway @Inject constructor() : MeshGateway {
    private data class Strip(val node: GatewayNode, var values: List<LedState>)
    private val strips = listOf(
        Strip(GatewayNode("DEMO-LOUNGE", 2, "Floor lamp"), List(6) { LedState(Rgb(255, 181, 107), 72, true) }),
        Strip(GatewayNode("DEMO-SHELF", 3, "Shelf glow"), List(17) { LedState(Rgb(235, 146, 115), 48, true) }),
        Strip(GatewayNode("DEMO-DESK", 4, "Desk strip"), List(300) { LedState(Rgb(140, 180, 211), 65, true) }),
        Strip(GatewayNode("DEMO-BEDROOM", 5, "Bedside light"), List(6) { LedState(Rgb(197, 166, 214), 32, false) })
    ).associateBy { it.node.uuid }.toMutableMap()
    private val rooms = mutableMapOf(0xC000 to ("Living room" to mutableSetOf("DEMO-LOUNGE", "DEMO-SHELF")), 0xC001 to ("Work corner" to mutableSetOf("DEMO-DESK")))
    private val _network = MutableStateFlow<GatewayNetwork?>(snapshot())
    override val network = _network.asStateFlow()
    private val _connection = MutableStateFlow(ConnectionStatus.CONNECTED)
    override val connection = _connection.asStateFlow()
    override val nearby = MutableStateFlow<List<NearbyLight>>(emptyList()).asStateFlow()
    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()
    private fun snapshot() = GatewayNetwork("demo", "The little things", strips.values.map { it.node }, rooms.map { it.key to it.value.first })
    override suspend fun awaitLoaded() = Unit
    override suspend fun createNetwork(name: String) = Unit
    override suspend fun renameNetwork(name: String) { _network.value = snapshot().copy(name = name) }
    override suspend fun renameNode(node: NodeInfo, name: String) {
        strips[node.uuid]?.let { strips[node.uuid] = it.copy(node = it.node.copy(name = name)) }
        _network.value = snapshot()
    }
    override suspend fun selectNetwork(id: String) = Unit
    override suspend fun networks() = listOf(NetworkChoice("demo", "The little things"))
    override fun setForeground(active: Boolean) { _connection.value = if (active) ConnectionStatus.CONNECTED else ConnectionStatus.IDLE }
    override fun setSessionEnabled(enabled: Boolean) { _connection.value = if (enabled) ConnectionStatus.CONNECTED else ConnectionStatus.IDLE }
    override fun discover() = Unit
    override fun stopDiscovery() = Unit
    override suspend fun connectProxy() { _connection.value = ConnectionStatus.CONNECTED }
    override suspend fun provision(light: NearbyLight): GatewayNode = error("Demo mode does not provision hardware")
    override suspend fun configure(node: NodeInfo, progress: suspend (SetupStage) -> Unit): Capabilities {
        progress(SetupStage.CAPABILITIES)
        return Capabilities(1, requireNotNull(strips[node.uuid]).values.size, 0x3F, 100)
    }
    override suspend fun capabilities(node: NodeInfo) = Capabilities(1, requireNotNull(strips[node.uuid]).values.size, 0x3F, 100)
    override suspend fun read(node: NodeInfo, target: Int): Observation {
        delay(30)
        val values = requireNotNull(strips[node.uuid]).values
        val observation = if (target == LedTarget.All) Observation(
            LedState(values.first().rgb, (values.sumOf { it.intensity } + values.size / 2) / values.size, values.any { it.power }),
            values.any { it.rgb != values.first().rgb }, values.any { it.intensity != values.first().intensity },
            values.any { it.power != values.first().power }, System.currentTimeMillis()
        ) else Observation(values[target], receivedAt = System.currentTimeMillis())
        _events.emit(GatewayEvent.Report(node.address, target, observation))
        return observation
    }
    override suspend fun write(node: NodeInfo, target: Int, patch: LightingPatch, acknowledged: Boolean): Observation? {
        delay(30)
        val strip = requireNotNull(strips[node.uuid])
        strip.values = strip.values.mapIndexed { index, state -> if (target == LedTarget.All || index == target) patch.applyTo(state) else state }
        return if (acknowledged) read(node, target) else null
    }
    override suspend fun standard(destination: Int, patch: LightingPatch, fadeMillis: Long) {
        val nodes = rooms[destination]?.second?.mapNotNull { strips[it]?.node } ?: strips.values.filter { it.node.address == destination }.map { it.node }
        nodes.forEach { write(NodeInfo(it.uuid, it.address, it.name), LedTarget.All, patch, false) }
    }
    override suspend fun groupWrite(destination: Int, node: NodeInfo, patch: LightingPatch) { standard(destination, patch) }
    override suspend fun renameRoom(address: Int, name: String) {
        rooms[address]?.let { rooms[address] = name to it.second }
        _network.value = snapshot()
    }
    override suspend fun createRoom(name: String): Int {
        val address = (0xC000..0xFEFF).first { it !in rooms }
        rooms[address] = name to mutableSetOf()
        _network.value = snapshot()
        return address
    }
    override suspend fun roomMembership(node: NodeInfo, address: Int, add: Boolean) {
        delay(60)
        require(!add || rooms.count { node.uuid in it.value.second } < 4 || node.uuid in requireNotNull(rooms[address]).second)
        if (add) requireNotNull(rooms[address]).second.add(node.uuid) else requireNotNull(rooms[address]).second.remove(node.uuid)
    }
    override suspend fun deleteRoom(address: Int) { rooms.remove(address); _network.value = snapshot() }
    override suspend fun identify(node: NodeInfo) { _events.emit(GatewayEvent.Log("Demo: identification preview")) }
    override suspend fun settings(node: NodeInfo) = NodeSettings(7, true, true, false)
    override suspend fun settings(node: NodeInfo, ttl: Int, relay: Boolean, proxy: Boolean) = Unit
    override suspend fun reset(node: NodeInfo) { strips.remove(node.uuid); _network.value = snapshot() }
    override suspend fun forget(node: NodeInfo) { strips.remove(node.uuid); _network.value = snapshot() }
    override suspend fun exportCdb(): String = error("Leave demo mode to export a real network")
    override suspend fun importCdb(json: String) = error("Leave demo mode to import a real network")
    override suspend fun disconnect() { _connection.value = ConnectionStatus.IDLE }

    fun restore(value: Installation) {
        strips.clear()
        value.nodes.forEach { node ->
            val count = node.capabilities?.ledCount ?: return@forEach
            val aggregate = node.aggregate
            val values = List(count) { index ->
                val individual = node.leds[index]
                val original = individual?.state ?: aggregate?.state ?: LedState(Rgb.White,100,true)
                if (aggregate != null && aggregate.receivedAt >= (individual?.receivedAt ?: 0)) original.copy(
                    rgb = if (aggregate.mixedRgb) original.rgb else aggregate.state.rgb,
                    intensity = if (aggregate.mixedIntensity) original.intensity else aggregate.state.intensity,
                    power = if (aggregate.mixedPower) original.power else aggregate.state.power
                ) else original
            }
            strips[node.uuid] = Strip(GatewayNode(node.uuid,node.address,node.name),values)
        }
        rooms.clear()
        value.rooms.forEach { rooms[it.address] = it.name to it.confirmed.toMutableSet() }
        _network.value = snapshot().copy(name = value.name)
    }

    fun installation(): Installation {
        val nodes = strips.values.map { strip ->
            NodeInfo(strip.node.uuid, strip.node.address, strip.node.name,
                capabilities = Capabilities(1, strip.values.size, 0x3F, 100), stage = SetupStage.READY,
                favorite = strip.node.address == 2,
                aggregate = Observation(strip.values.first(), receivedAt = System.currentTimeMillis()),
                leds = strip.values.mapIndexed { i, state -> i to Observation(state, receivedAt = System.currentTimeMillis()) }.toMap())
        }
        val preset = PresetInfo(UUID.nameUUIDFromBytes("soft-evening".toByteArray()).toString(), "Soft evening",
            nodes.filter { it.address in 2..3 }.flatMap { node ->
                List(node.capabilities!!.ledCount) { PresetEdit(LedRef(node.uuid, it), LightingPatch(Rgb(255, 173, 104), 35, true)) }
            })
        return Installation(networkId = "demo", name = "The little things", nodes = nodes,
            rooms = rooms.map { RoomInfo(it.key, it.value.first, it.value.second.toSet(), it.value.second.toSet()) },
            zones = listOf(ZoneInfo("demo-zone", "Reading nook", listOf(LedRef("DEMO-LOUNGE", 0), LedRef("DEMO-LOUNGE", 1)))),
            presets = listOf(preset))
    }
}
