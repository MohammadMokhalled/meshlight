package com.example.meshlightcontroller.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.meshlightcontroller.R
import com.example.meshlightcontroller.core.model.*

@Composable
fun GroupsScreen(state: ControllerState, modifier: Modifier = Modifier, onNavigate: (Route)->Unit) {
    var zones by rememberSaveable {mutableStateOf(false)}
    val installation=state.installation ?: return
    val createLabel=stringResource(if(zones) R.string.create_zone else R.string.create_room)
    Box(modifier.fillMaxSize()) {
        LazyColumn(contentPadding=PaddingValues(start=24.dp,end=24.dp,top=16.dp,bottom=104.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {PageHeading(stringResource(R.string.groups),subtitle=stringResource(if(zones) R.string.zone_explanation else R.string.room_explanation))}
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(!zones,{zones=false},label={Text(stringResource(R.string.rooms))})
                    FilterChip(zones,{zones=true},label={Text(stringResource(R.string.zones))})
                }
            }
            if(zones) {
                if(installation.zones.isEmpty()) item {EmptyState(stringResource(R.string.empty_zones),stringResource(R.string.empty_zones_body),glyph=Glyph.ZONE)}
                items(installation.zones,key={it.id}) {zone ->
                    GroupRow(zone.name,stringResource(R.string.led_count,zone.leds.size),Glyph.ZONE,needsAttention=zone.invalidated) {onNavigate(Route("zone",zone.id))}
                }
            } else {
                if(installation.rooms.isEmpty()) item {EmptyState(stringResource(R.string.empty_rooms),stringResource(R.string.empty_rooms_body),glyph=Glyph.GROUP)}
                items(installation.rooms,key={it.address}) {room ->
                    val partial=room.confirmed!=room.members||room.failures.isNotEmpty()||room.pendingRemoval.isNotEmpty()||room.controlFailures.isNotEmpty()
                    GroupRow(room.name,if(room.deleting) stringResource(R.string.room_delete_pending) else if(partial) stringResource(R.string.room_confirmed,room.confirmed.size,room.members.size) else stringResource(R.string.light_count,room.members.size),Glyph.GROUP,needsAttention=partial) {onNavigate(Route("room",room.address.toString()))}
                }
            }
        }
        if(!state.readOnly) ExtendedFloatingActionButton(onClick={onNavigate(Route(if(zones) "zoneEdit" else "roomEdit"))},modifier=Modifier.align(Alignment.BottomEnd).padding(24.dp).semantics {contentDescription=createLabel},icon={MeshIcon(Glyph.ADD)},text={Text(createLabel)},containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun GroupRow(name: String, subtitle: String, glyph: Glyph, modifier: Modifier = Modifier, needsAttention: Boolean = false, onClick: ()->Unit) {
    Surface(onClick=onClick,modifier=modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer,MaterialTheme.shapes.medium),contentAlignment=Alignment.Center) {MeshIcon(glyph,tint=MaterialTheme.colorScheme.onSecondaryContainer)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(name,style=MaterialTheme.typography.titleMedium)
                Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(needsAttention) Text(stringResource(if(glyph==Glyph.ZONE) R.string.remap_required else R.string.room_pending),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
            }
            MeshIcon(Glyph.ARROW,Modifier.size(18.dp))
        }
    }
}

@Composable
fun RoomDetailScreen(room: RoomInfo, state: ControllerState, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onNavigate: (Route)->Unit, onBack: ()->Unit) {
    var delete by rememberSaveable {mutableStateOf(false)}
    val members=state.installation?.nodes.orEmpty().filter {it.uuid in room.members}
    val observations=members.mapNotNull {previewObservation(it,setOf(LedTarget.All))}
    val observation=if(observations.size==members.size) combineObservations(observations) else null
    val partial=room.members!=room.confirmed||room.pendingRemoval.isNotEmpty()||room.failures.isNotEmpty()
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {DetailTopBar(room.name,onBack=onBack) {
            ActionIcon(Glyph.EDIT,R.string.edit_members,{onNavigate(Route("roomEdit",room.address.toString()))},enabled=!state.readOnly&&!room.deleting)
            ActionIcon(Glyph.DELETE,R.string.delete,{delete=true},enabled=!state.readOnly)
        }}
        item {LightOrb(room.draft.rgb ?: observation?.state?.rgb,Modifier.fillMaxWidth().height(140.dp))}
        if(partial) item {InfoCard(stringResource(R.string.room_partial_help),Modifier.padding(horizontal=24.dp),error=true) {
            Text(stringResource(R.string.room_confirmed,room.confirmed.size,room.members.size),style=MaterialTheme.typography.bodySmall)
            if(room.pendingRemoval.isNotEmpty()) Text(stringResource(R.string.room_remove_pending,room.pendingRemoval.size),style=MaterialTheme.typography.bodySmall)
            room.failures.values.distinct().forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
            Button({onAction(UiAction.RetryRoom(room.address))},enabled=!state.readOnly) {Text(stringResource(R.string.retry))}
        }}
        if(!room.deleting) item(key="controls") {
            LightingControls(observation,Modifier.padding(horizontal=24.dp),room.draft,capabilities=sharedCapabilities(members),favoriteColors=state.installation?.favoriteColors.orEmpty(),recentColors=state.installation?.recentColors.orEmpty(),enabled=!state.readOnly&&!partial&&members.isNotEmpty(),
                onFavorite={onAction(UiAction.FavoriteColor(it))}) {patch,final->onAction(UiAction.EditRoom(room.address,patch,final))}
        }
        if(room.controlFailures.isNotEmpty()) item {
            InfoCard(stringResource(R.string.room_verification_help),Modifier.padding(horizontal=24.dp),error=true) {
                room.controlFailures.values.distinct().forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
            }
        }
        if(!room.draft.isEmpty && "room:${room.address}/${LedTarget.All}" !in state.pendingTargets) item {InfoCard(stringResource(if(state.connection==ConnectionStatus.CONNECTED) R.string.draft_pending else R.string.offline_preview),Modifier.padding(horizontal=24.dp)) {
            Button({onAction(UiAction.ApplyRoomDraft(room.address))},enabled=state.connection==ConnectionStatus.CONNECTED&&!partial&&!state.readOnly){Text(stringResource(R.string.apply_changes))}
        }}
        item {Text(stringResource(R.string.uniform_room_note),Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {SectionLabel(stringResource(R.string.members),Modifier.padding(horizontal=24.dp))}
        items(members,key={it.uuid}) {node ->GroupRow(node.name,stringResource(if(node.uuid in room.confirmed) R.string.success else R.string.setup_needed),Glyph.LIGHT,modifier=Modifier.padding(horizontal=24.dp)) {onNavigate(Route("light",node.uuid))}}
    }
    if(delete) ConfirmDialog(stringResource(R.string.delete_room),stringResource(R.string.delete_room_body),stringResource(R.string.delete),{delete=false}) {onAction(UiAction.DeleteRoom(room.address));delete=false;onBack()}
}

@Composable
fun RoomEditorScreen(room: RoomInfo?, state: ControllerState, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onBack: ()->Unit) {
    var name by rememberSaveable(room?.address) {mutableStateOf(room?.name.orEmpty())}
    var selected by rememberSaveable(room?.address,stateSaver=listSaver<Set<String>,String>(save={it.toList()},restore={it.toSet()})) {mutableStateOf(room?.members.orEmpty())}
    val nodes=state.installation?.nodes.orEmpty().filter {it.stage==SetupStage.READY}
    Column(modifier.fillMaxSize()) {
        DetailTopBar(stringResource(if(room==null) R.string.create_room else R.string.edit_members),onBack=onBack) {
            TextButton({onAction(UiAction.SaveRoom(name,selected,room?.address));onBack()},enabled=name.isNotBlank()&&selected.isNotEmpty()&&!state.readOnly) {Text(stringResource(R.string.save))}
        }
        LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {OutlinedTextField(name,{name=it.take(80)},label={Text(stringResource(R.string.room_name))},modifier=Modifier.fillMaxWidth(),singleLine=true)}
            item {Text(stringResource(R.string.room_explanation),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            item {InfoCard(stringResource(R.string.room_capacity))}
            if(nodes.isEmpty()) item {Text(stringResource(R.string.no_ready_lights))}
            items(nodes,key={it.uuid}) {node ->
                val count=state.installation?.rooms.orEmpty().count {it.address!=room?.address && (node.uuid in it.members||node.uuid in it.pendingRemoval)}
                CheckRow(node.name,node.uuid in selected,enabled=count<4||node.uuid in selected) {checked-> selected=if(checked) selected+node.uuid else selected-node.uuid}
            }
        }
    }
}

@Composable
fun ZoneDetailScreen(zone: ZoneInfo, state: ControllerState, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onNavigate: (Route)->Unit, onBack: ()->Unit) {
    var red by rememberSaveable(zone.id) {mutableStateOf<String?>(null)}
    var intensity by rememberSaveable(zone.id) {mutableStateOf<Int?>(null)}
    var power by rememberSaveable(zone.id) {mutableStateOf<Boolean?>(null)}
    var delete by rememberSaveable {mutableStateOf(false)}
    val patch=LightingPatch(red?.let(Rgb::fromHex),intensity,power)
    val values=zone.leds.mapNotNull {ref->state.installation?.nodes?.firstOrNull {it.uuid==ref.nodeUuid}?.let {previewObservation(it,setOf(ref.index))}}
    val observation=if(values.size==zone.leds.size) combineObservations(values) else null
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {DetailTopBar(zone.name,onBack=onBack) {
            ActionIcon(Glyph.EDIT,R.string.edit_zone,{onNavigate(Route("zoneEdit",zone.id))},enabled=!state.readOnly)
            ActionIcon(Glyph.DELETE,R.string.delete,{delete=true},enabled=!state.readOnly)
        }}
        if(zone.invalidated) item {InfoCard(stringResource(R.string.remap_help),Modifier.padding(horizontal=24.dp),error=true) {TextButton({onNavigate(Route("zoneEdit",zone.id))}) {Text(stringResource(R.string.edit_zone))}}}
        item {LightOrb(patch.rgb ?: observation?.state?.rgb,Modifier.fillMaxWidth().height(140.dp))}
        item {Text(stringResource(R.string.led_count,zone.leds.size),Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.titleMedium)}
        item {LightingControls(observation,Modifier.padding(horizontal=24.dp),patch,capabilities=sharedCapabilities(state.installation?.nodes.orEmpty().filter {node->zone.leds.any {it.nodeUuid==node.uuid}}),favoriteColors=state.installation?.favoriteColors.orEmpty(),recentColors=state.installation?.recentColors.orEmpty(),enabled=!zone.invalidated&&!state.readOnly,
            onFavorite={onAction(UiAction.FavoriteColor(it))}) {new,_ -> new.rgb?.let {red=it.hex};new.intensity?.let {intensity=it};new.power?.let {power=it} }}
        item {Text(stringResource(R.string.zone_apply_note),Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Button({onAction(UiAction.EditZone(zone.id,patch));red=null;intensity=null;power=null},Modifier.fillMaxWidth().padding(horizontal=24.dp),enabled=!patch.isEmpty&&!zone.invalidated&&!state.readOnly) {Text(stringResource(R.string.apply_changes))}}
        items(zone.leds.groupBy {it.nodeUuid}.toList(),key={it.first}) { (uuid,refs) ->
            val node=state.installation?.nodes?.firstOrNull {it.uuid==uuid}
            if(node!=null) GroupRow(node.name,stringResource(R.string.led_count,refs.size),Glyph.LEDS,modifier=Modifier.padding(horizontal=24.dp)) {onNavigate(Route("strip",uuid))}
        }
    }
    if(delete) ConfirmDialog(stringResource(R.string.delete_zone),stringResource(R.string.delete_zone_body),stringResource(R.string.delete),{delete=false}) {onAction(UiAction.DeleteZone(zone.id));delete=false;onBack()}
}

@Composable
fun ZoneEditorScreen(zone: ZoneInfo?, state: ControllerState, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onBack: ()->Unit) {
    val nodes=state.installation?.nodes.orEmpty().filter {it.stage==SetupStage.READY&&it.capabilities?.supportsIndexed==true}
    var name by rememberSaveable(zone?.id) {mutableStateOf(zone?.name.orEmpty())}
    var selected by rememberSaveable(zone?.id,stateSaver=listSaver<Set<String>,String>(save={it.toList()},restore={it.toSet()})) {
        mutableStateOf(zone?.leds.orEmpty().filter {ref->nodes.any {it.uuid==ref.nodeUuid&&ref.index<(it.capabilities?.ledCount ?: 0)}}.map {"${it.nodeUuid}|${it.index}"}.toSet())
    }
    var rangeNode by remember {mutableStateOf<NodeInfo?>(null)}
    val refs=selected.map {val parts=it.split('|');LedRef(parts[0],parts[1].toInt())}
    Column(modifier.fillMaxSize()) {
        DetailTopBar(stringResource(if(zone==null) R.string.create_zone else R.string.edit_zone),onBack=onBack) {
            TextButton({onAction(UiAction.SaveZone(name,refs,zone?.id));onBack()},enabled=name.isNotBlank()&&refs.isNotEmpty()&&!state.readOnly) {Text(stringResource(R.string.save))}
        }
        LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {OutlinedTextField(name,{name=it.take(80)},label={Text(stringResource(R.string.zone_name))},singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {Text(stringResource(R.string.zone_explanation),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            item {Text(stringResource(R.string.selected_count,refs.size),style=MaterialTheme.typography.titleMedium)}
            if(nodes.isEmpty()) item {Text(stringResource(R.string.no_ready_lights))}
            items(nodes,key={it.uuid}) {node ->
                val count=refs.count {it.nodeUuid==node.uuid}
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(node.name,style=MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.selected_count,count),style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton({selected=selected+(0 until (node.capabilities?.ledCount ?: 0)).map {"${node.uuid}|$it"}}) {Text(stringResource(R.string.select_all))}
                            TextButton({rangeNode=node}) {Text(stringResource(R.string.range))}
                            TextButton({selected=selected.filterNot {it.startsWith(node.uuid+"|")}.toSet()}) {Text(stringResource(R.string.select_none))}
                        }
                        if(count>0) Text(refs.filter {it.nodeUuid==node.uuid}.map {it.index+1}.sorted().joinToString(", "),style=MaterialTheme.typography.bodySmall,maxLines=3)
                    }
                }
            }
        }
    }
    rangeNode?.let {node ->LedRangeDialog(node.capabilities?.ledCount ?: 0,{rangeNode=null}) {from,to->selected=selected+(from..to).map {"${node.uuid}|$it"};rangeNode=null}}
}
