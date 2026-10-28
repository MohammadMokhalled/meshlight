package com.example.meshlightcontroller.data

import com.example.meshlightcontroller.core.control.CommandPlanner
import com.example.meshlightcontroller.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import java.util.UUID

fun LightingRepository.references(uuid: String): List<LedRef> =
    state.value.installation?.nodes?.firstOrNull { it.uuid == uuid }?.let { node ->
        List(node.capabilities?.ledCount ?: 0) { LedRef(uuid, it) }
    }.orEmpty()

fun LightingRepository.roomReferences(address: Int): List<LedRef> =
    room(address).members.flatMap(::references)

fun LightingRepository.capturePreset(name: String, refs: List<LedRef>) = startOperation(OperationKind.CAPTURE, refs.distinct().size) {
    checkEditable()
    require(name.isNotBlank() && refs.isNotEmpty())
    val edits = mutableListOf<PresetEdit>()
    for (ref in refs.distinct()) {
        currentCoroutineContext().ensureActive()
        val node = node(ref.nodeUuid)
        require(ref.index in 0 until requireNotNull(node.capabilities).ledCount)
        val existing = node.leds[ref.index]
        val observation = if (existing != null && System.currentTimeMillis() - existing.receivedAt < 30_000) existing
            else gateway.read(node, ref.index)
        edits += PresetEdit(ref, LightingPatch(observation.state.rgb, observation.state.intensity, observation.state.power))
        advance()
    }
    val preset = PresetInfo(UUID.randomUUID().toString(), name.trim(), edits)
    updateInstallation { it.copy(presets = it.presets + preset) }
    saveNow()
}

fun LightingRepository.savePattern(name: String, refs: List<LedRef>, first: Rgb, second: Rgb, alternating: Boolean) {
    require(name.isNotBlank() && refs.isNotEmpty())
    val edits = if (alternating) refs.distinct().mapIndexed { index, ref ->
        PresetEdit(ref, LightingPatch(rgb = if (index % 2 == 0) first else second))
    } else CommandPlanner.gradient(refs, first, second)
    CommandPlanner.preset(installation(), edits)
    updateInstallation { it.copy(presets = it.presets + PresetInfo(UUID.randomUUID().toString(), name.trim(), edits)) }
}

fun LightingRepository.applyPreset(id: String) = startOperation(OperationKind.APPLY) {
    checkEditable()
    val preset = requireNotNull(installation().presets.firstOrNull { it.id == id })
    require(!preset.invalidated) { "Recapture this preset after the light was reset or changed" }
    val edits = CommandPlanner.preset(installation(), preset.edits)
    mutableState.update { it.copy(operation = it.operation?.copy(total = edits.size)) }
    if (state.value.connection != ConnectionStatus.CONNECTED) gateway.connectProxy()
    batch(edits)
}

fun LightingRepository.deletePreset(id: String) { updateInstallation { it.copy(presets = it.presets.filterNot { preset -> preset.id == id }) } }
fun LightingRepository.renamePreset(id: String, name: String) {
    require(name.isNotBlank())
    updateInstallation { it.copy(presets = it.presets.map { preset -> if (preset.id == id) preset.copy(name = name.trim()) else preset }) }
}
fun LightingRepository.duplicatePreset(id: String, name: String) {
    require(name.isNotBlank())
    val preset = requireNotNull(installation().presets.firstOrNull { it.id == id })
    updateInstallation { it.copy(presets = it.presets + preset.copy(id = UUID.randomUUID().toString(), name = name.trim())) }
}

fun LightingRepository.retryDrafts() = startOperation(OperationKind.APPLY) {
    checkEditable()
    val edits = installation().nodes.flatMap { node -> node.drafts.map { (target, patch) ->
        com.example.meshlightcontroller.core.control.PlannedEdit(node.uuid, target, patch)
    } }
    mutableState.update { it.copy(operation = it.operation?.copy(total = edits.size.coerceAtLeast(1))) }
    if (state.value.connection != ConnectionStatus.CONNECTED) gateway.connectProxy()
    batch(edits)
}
