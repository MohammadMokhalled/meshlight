package com.example.meshlightcontroller.core.mesh

import com.example.meshlightcontroller.core.model.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class GatewayNode(val uuid: String, val address: Int, val name: String, val subscriptions: Set<Int> = emptySet())
data class GatewayNetwork(val id: String, val name: String, val nodes: List<GatewayNode>, val rooms: List<Pair<Int, String>>)
sealed interface GatewayEvent {
    data class Report(val source: Int, val target: Int, val observation: Observation) : GatewayEvent
    data class Transition(val source: Int, val value: TransitionInfo?) : GatewayEvent
    data class Log(val text: String) : GatewayEvent
    data class Error(val text: String) : GatewayEvent
}

interface MeshGateway {
    val network: StateFlow<GatewayNetwork?>
    val connection: StateFlow<ConnectionStatus>
    val nearby: StateFlow<List<NearbyLight>>
    val events: SharedFlow<GatewayEvent>
    suspend fun awaitLoaded()
    suspend fun createNetwork(name: String)
    suspend fun renameNetwork(name: String)
    suspend fun renameNode(node: NodeInfo, name: String)
    suspend fun selectNetwork(id: String)
    suspend fun networks(): List<NetworkChoice>
    fun setForeground(active: Boolean)
    fun setSessionEnabled(enabled: Boolean)
    fun discover()
    fun stopDiscovery()
    suspend fun connectProxy()
    suspend fun provision(light: NearbyLight): GatewayNode
    suspend fun configure(node: NodeInfo, progress: suspend (SetupStage) -> Unit): Capabilities
    suspend fun capabilities(node: NodeInfo): Capabilities
    suspend fun read(node: NodeInfo, target: Int): Observation
    suspend fun write(node: NodeInfo, target: Int, patch: LightingPatch, acknowledged: Boolean): Observation?
    suspend fun standard(destination: Int, patch: LightingPatch, fadeMillis: Long = 0)
    suspend fun groupWrite(destination: Int, node: NodeInfo, patch: LightingPatch)
    suspend fun renameRoom(address: Int, name: String)
    suspend fun createRoom(name: String): Int
    suspend fun roomMembership(node: NodeInfo, address: Int, add: Boolean)
    suspend fun deleteRoom(address: Int)
    suspend fun identify(node: NodeInfo)
    suspend fun settings(node: NodeInfo): NodeSettings
    suspend fun settings(node: NodeInfo, ttl: Int, relay: Boolean, proxy: Boolean)
    suspend fun reset(node: NodeInfo)
    suspend fun forget(node: NodeInfo)
    suspend fun exportCdb(): String
    suspend fun importCdb(json: String)
    suspend fun disconnect()
}

data class NodeSettings(val ttl: Int, val relay: Boolean, val proxy: Boolean, val currentProxy: Boolean)
