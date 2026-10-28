package com.example.meshlightcontroller.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.meshlightcontroller.R
import com.example.meshlightcontroller.core.model.*

@Composable
fun PresetsScreen(state: ControllerState, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onNavigate: (Route)->Unit) {
    var create by rememberSaveable {mutableStateOf(false)}
    val createLabel=stringResource(R.string.new_preset)
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(240.dp),contentPadding=PaddingValues(start=24.dp,end=24.dp,top=16.dp,bottom=104.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item(span={GridItemSpan(maxLineSpan)}) {PageHeading(stringResource(R.string.presets),subtitle=stringResource(R.string.empty_presets_body))}
            if(state.installation?.presets.isNullOrEmpty()) item(span={GridItemSpan(maxLineSpan)}) {EmptyState(stringResource(R.string.empty_presets),stringResource(R.string.empty_presets_body),glyph=Glyph.PRESET)}
            items(state.installation?.presets.orEmpty(),key={it.id}) {preset ->
                Surface(onClick={onNavigate(Route("preset",preset.id))},shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        PresetArt(preset,Modifier.fillMaxWidth().height(126.dp))
                        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(preset.name,style=MaterialTheme.typography.titleLarge)
                            Text(stringResource(if(preset.invalidated) R.string.recapture_required else R.string.preset_leds,preset.edits.size),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Button({onAction(UiAction.ApplyPreset(preset.id))},enabled=!preset.invalidated&&!state.readOnly) {Text(stringResource(R.string.apply))}
                        }
                    }
                }
            }
        }
        if(!state.readOnly) ExtendedFloatingActionButton(onClick={create=true},modifier=Modifier.align(Alignment.BottomEnd).padding(24.dp).semantics {contentDescription=createLabel},icon={MeshIcon(Glyph.ADD)},text={Text(createLabel)},containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary)
    }
    if(create) CreatePresetDialog(requireNotNull(state.installation),{create=false},onAction)
}

@Composable
fun PresetArt(preset: PresetInfo, modifier: Modifier = Modifier) {
    val colors=preset.edits.mapNotNull {it.patch.rgb}.distinct().take(8).map {it.composeColor()}.ifEmpty {listOf(MaterialTheme.colorScheme.secondaryContainer,MaterialTheme.colorScheme.tertiaryContainer)}
    Box(modifier.background(Brush.horizontalGradient(if(colors.size==1) colors+colors else colors)),contentAlignment=Alignment.Center) {
        MeshIcon(Glyph.PRESET,Modifier.size(40.dp),MaterialTheme.colorScheme.onSurface.copy(alpha=.6f))
    }
}

@Composable
fun PresetDetailScreen(preset: PresetInfo, state: ControllerState, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onBack: ()->Unit) {
    var rename by rememberSaveable {mutableStateOf(false)}
    var duplicate by rememberSaveable {mutableStateOf(false)}
    var delete by rememberSaveable {mutableStateOf(false)}
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {DetailTopBar(preset.name,onBack=onBack) {
            ActionIcon(Glyph.EDIT,R.string.rename,{rename=true},enabled=!state.readOnly)
            ActionIcon(Glyph.DELETE,R.string.delete,{delete=true},enabled=!state.readOnly)
        }}
        item {PresetArt(preset,Modifier.fillMaxWidth().padding(horizontal=24.dp).height(200.dp))}
        item {Text(stringResource(R.string.preset_leds,preset.edits.size),Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.titleLarge)}
        if(preset.invalidated) item {InfoCard(stringResource(R.string.preset_invalid_help),Modifier.padding(horizontal=24.dp),error=true)}
        item {Button({onAction(UiAction.ApplyPreset(preset.id))},Modifier.fillMaxWidth().padding(horizontal=24.dp),enabled=!preset.invalidated&&!state.readOnly) {Text(stringResource(R.string.apply_preset,preset.name))}}
        item {Text(stringResource(R.string.zone_apply_note),Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {TextButton({duplicate=true},Modifier.padding(horizontal=24.dp),enabled=!state.readOnly){MeshIcon(Glyph.COPY,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(stringResource(R.string.duplicate))}}
        items(preset.edits.groupBy {it.ref.nodeUuid}.toList(),key={it.first}) {(uuid,edits)->
            val node=state.installation?.nodes?.firstOrNull {it.uuid==uuid}
            Column(Modifier.padding(horizontal=24.dp)) {
                Text(node?.name ?: stringResource(R.string.not_available),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.led_count,edits.size),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val colors=edits.mapNotNull {it.patch.rgb}.distinct().take(8)
                if(colors.isNotEmpty()) ColorSwatches(colors,enabled=false,onColor={})
            }
        }
    }
    if(rename) NameDialog(stringResource(R.string.rename),stringResource(R.string.preset_name),preset.name,{rename=false}) {onAction(UiAction.RenamePreset(preset.id,it));rename=false}
    if(duplicate) NameDialog(stringResource(R.string.duplicate),stringResource(R.string.preset_name),preset.name,{duplicate=false}) {onAction(UiAction.DuplicatePreset(preset.id,it));duplicate=false}
    if(delete) ConfirmDialog(stringResource(R.string.delete_preset),stringResource(R.string.delete_preset_body),stringResource(R.string.delete),{delete=false}) {onAction(UiAction.DeletePreset(preset.id));delete=false;onBack()}
}

private data class CaptureTarget(val id: String, val label: String, val refs: List<LedRef>)

@Composable
fun CreatePresetDialog(installation: Installation, onDismiss: ()->Unit, onAction: (UiAction)->Unit) {
    val all=installation.nodes.filter {it.stage==SetupStage.READY}.flatMap {node->List(node.capabilities?.ledCount ?: 0){LedRef(node.uuid,it)}}
    val targets=buildList {
        add(CaptureTarget("all",stringResource(R.string.capture_all),all))
        installation.nodes.filter {it.stage==SetupStage.READY}.forEach {node->add(CaptureTarget(node.uuid,node.name,all.filter {it.nodeUuid==node.uuid}))}
        installation.rooms.filter {!it.deleting&&it.pendingRemoval.isEmpty()}.forEach {room->add(CaptureTarget("room:${room.address}",room.name,all.filter {it.nodeUuid in room.members}))}
        installation.zones.filterNot {it.invalidated}.forEach {add(CaptureTarget(it.id,it.name,it.leds))}
    }
    var target by rememberSaveable {mutableStateOf("all")}
    var name by rememberSaveable {mutableStateOf("")}
    var pattern by rememberSaveable {mutableStateOf(false)}
    var modePattern by rememberSaveable {mutableStateOf(false)}
    var expanded by remember {mutableStateOf(false)}
    val selected=targets.firstOrNull {it.id==target} ?: targets.first()
    if(pattern) {PatternDialog(selected.refs,onDismiss,onAction);return}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.new_preset))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(!modePattern,{modePattern=false},label={Text(stringResource(R.string.mode_capture))})
                FilterChip(modePattern,{modePattern=true},label={Text(stringResource(R.string.mode_pattern))})
            }
            if(!modePattern) OutlinedTextField(name,{name=it.take(80)},label={Text(stringResource(R.string.preset_name))},singleLine=true)
            Text(stringResource(R.string.choose_target),style=MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton({expanded=true},Modifier.fillMaxWidth()) {Text(selected.label);Spacer(Modifier.weight(1f));MeshIcon(Glyph.ARROW,Modifier.size(18.dp))}
                DropdownMenu(expanded,{expanded=false}) {targets.forEach {item->DropdownMenuItem(text={Text(item.label)},onClick={target=item.id;expanded=false})}}
            }
            Text(stringResource(R.string.selected_count,selected.refs.size),style=MaterialTheme.typography.labelLarge)
            Text(stringResource(if(modePattern) R.string.pattern_rgb_only else R.string.capture_description),style=MaterialTheme.typography.bodySmall)
            if(selected.refs.isEmpty()) Text(stringResource(R.string.no_targets),color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton({if(modePattern) pattern=true else {onAction(UiAction.CapturePreset(name,selected.refs));onDismiss()}},enabled=selected.refs.isNotEmpty()&&(modePattern||name.isNotBlank())) {Text(stringResource(if(modePattern) R.string.create_pattern else R.string.capture_look))}},dismissButton={TextButton(onDismiss){Text(stringResource(R.string.cancel))}})
}
