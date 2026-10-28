package com.example.meshlightcontroller.data

import com.example.meshlightcontroller.core.control.*
import com.example.meshlightcontroller.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import java.util.UUID

fun LightingRepository.saveRoom(name: String, members: Set<String>, address: Int? = null) =
    startOperation(OperationKind.GROUP, members.size.coerceAtLeast(1)) {
        checkEditable()
        require(name.isNotBlank())
        val allocated = address ?: gateway.createRoom(name.trim())
        val old = installation().rooms.firstOrNull { it.address == allocated } ?: RoomInfo(allocated, name.trim())
        updateRoom(old.copy(name = name.trim(), members = members, confirmed = old.confirmed.intersect(members),
            pendingRemoval = old.pendingRemoval + (old.members - members), failures = emptyMap()))
        saveNow()
        if (address != null) gateway.renameRoom(allocated, name.trim())
        reconcileRoom(allocated)
    }

fun LightingRepository.retryRoom(address: Int) = startOperation(OperationKind.GROUP, room(address).members.size + room(address).pendingRemoval.size) {
    checkEditable()
    reconcileRoom(address)
}

fun LightingRepository.deleteRoom(address: Int) = startOperation(OperationKind.GROUP, room(address).members.size + room(address).pendingRemoval.size) {
    checkEditable()
    val value = room(address)
    updateRoom(value.copy(deleting = true, members = emptySet(), pendingRemoval = value.pendingRemoval + value.members + value.confirmed, confirmed = emptySet()))
    saveNow()
    reconcileRoom(address)
}

internal suspend fun LightingRepository.reconcileRoom(address: Int) {
    val target = room(address)
    if (target.members.isNotEmpty() || target.pendingRemoval.isNotEmpty()) {
        if (state.value.connection != ConnectionStatus.CONNECTED) gateway.connectProxy()
    }
    for (uuid in (target.members + target.pendingRemoval)) {
        currentCoroutineContext().ensureActive()
        try {
            val add = uuid in room(address).members
            gateway.roomMembership(node(uuid), address, add)
            val latest = room(address)
            updateRoom(latest.copy(confirmed = if (add) latest.confirmed + uuid else latest.confirmed - uuid,
                pendingRemoval = latest.pendingRemoval - uuid, failures = latest.failures - uuid))
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            updateRoom(room(address).let { it.copy(failures = it.failures + (uuid to safe(error))) })
            failure(uuid, error)
        }
        advance()
        saveNow()
    }
    val latest = room(address)
    if (latest.deleting && latest.pendingRemoval.isEmpty()) {
        gateway.deleteRoom(address)
        updateInstallation { it.copy(rooms = it.rooms.filterNot { group -> group.address == address }) }
        saveNow()
    }
}

fun LightingRepository.editRoom(address: Int, patch: LightingPatch, final: Boolean = true) {
    checkEditable()
    if (patch.isEmpty) return
    if (state.value.operation?.kind in setOf(OperationKind.APPLY, OperationKind.REFRESH, OperationKind.CAPTURE)) cancelOperation()
    backgroundRead?.cancel()
    val value = room(address)
    require(!value.deleting && value.pendingRemoval.isEmpty() && value.members == value.confirmed) { "Finish configuring this room first" }
    updateRoom(value.copy(draft = value.draft.merge(patch)))
    if (state.value.connection != ConnectionStatus.CONNECTED) {
        forgetReconnectEdits("room:$address")
        return
    }
    val key = EditKey("room:$address", LedTarget.All)
    val live = liveEdits[key]?.merge(patch) ?: patch
    liveEdits[key] = live
    mutableState.update { it.copy(pendingTargets = it.pendingTargets + token(key)) }
    scheduler.enqueue(key, if (final) live else patch, final)
}

fun LightingRepository.applyRoomDraft(address: Int) {
    val patch = room(address).draft
    if (!patch.isEmpty) editRoom(address, patch, true)
}

internal suspend fun LightingRepository.sendRoomEdit(edit: ScheduledEdit) {
    val address = edit.key.nodeUuid.removePrefix("room:").toInt()
    val value = room(address)
    require(value.pendingRemoval.isEmpty() && !value.deleting)
    val members = value.members.map(::node)
    require(members.isNotEmpty() && members.all { it.stage == SetupStage.READY }) { "Finish setting up room lights first" }
    if (edit.patch.rgb == null && (edit.patch.power == null || edit.patch.intensity == null)) {
        gateway.standard(address, edit.patch)
    } else {
        members.distinctBy { it.companyId to it.modelId }.forEach { gateway.groupWrite(address, it, edit.patch) }
    }
    if (edit.final) {
        for (member in members) {
            currentCoroutineContext().ensureActive()
            try {
                val observed = gateway.read(member, LedTarget.All)
                record(member.uuid, LedTarget.All, observed)
                check(observed.matches(edit.patch)) { "Room edit is not verified on this light" }
                updateNode(member.uuid) { it.copy(lastError = null) }
                updateRoom(room(address).let { it.copy(controlFailures = it.controlFailures - member.uuid) })
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                updateNode(member.uuid) { it.copy(lastError = safe(error)) }
                updateRoom(room(address).let { it.copy(controlFailures = it.controlFailures + (member.uuid to safe(error))) })
            }
        }
        check(members.none { it.uuid in room(address).controlFailures }) { "Some room lights could not be verified; refresh or retry" }
    }
}

internal fun LightingRepository.finishRoomEdit(edit: ScheduledEdit, failure: Throwable?) {
    val address = edit.key.nodeUuid.removePrefix("room:").toInt()
    if (failure == null && edit.final) {
        liveEdits.remove(edit.key)
        updateRoom(room(address).let { it.copy(draft = it.draft.without(edit.patch)) })
    } else if (failure != null) { liveEdits.remove(edit.key); message(failure) }
    mutableState.update { it.copy(pendingTargets = it.pendingTargets - token(edit.key)) }
}

internal fun LightingRepository.room(address: Int) = requireNotNull(installation().rooms.firstOrNull { it.address == address })
internal fun LightingRepository.updateRoom(room: RoomInfo) {
    updateInstallation { it.copy(rooms = it.rooms.filterNot { value -> value.address == room.address } + room) }
}

fun LightingRepository.saveZone(name: String, references: List<LedRef>, id: String? = null) {
    require(name.isNotBlank() && references.isNotEmpty())
    CommandPlanner.zone(installation(), references, LightingPatch(power = true))
    val zone = ZoneInfo(id ?: UUID.randomUUID().toString(), name.trim(), references.distinct())
    updateInstallation { it.copy(zones = it.zones.filterNot { previous -> previous.id == zone.id } + zone) }
}
fun LightingRepository.deleteZone(id: String) { updateInstallation { it.copy(zones = it.zones.filterNot { zone -> zone.id == id }) } }

fun LightingRepository.editZone(id: String, patch: LightingPatch) = startOperation(OperationKind.APPLY) {
    checkEditable()
    val zone = requireNotNull(installation().zones.firstOrNull { it.id == id })
    require(!zone.invalidated) { "Edit this zone to remap its LEDs" }
    val edits = CommandPlanner.zone(installation(), zone.leds, patch)
    mutableState.update { it.copy(operation = it.operation?.copy(total = edits.size)) }
    if (state.value.connection != ConnectionStatus.CONNECTED) gateway.connectProxy()
    batch(edits)
}
