package com.example.meshlightcontroller.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.meshlightcontroller.R
import com.example.meshlightcontroller.core.model.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun StripScreen(node: NodeInfo, state: ControllerState, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onBack: ()->Unit) {
    val count=node.capabilities?.ledCount ?: 0
    var selected by rememberSaveable(node.uuid,count,stateSaver=listSaver<Set<Int>,Int>(save={it.toList()},restore={it.toSet()})) {mutableStateOf(if(count>0) setOf(0) else emptySet())}
    var multiSelect by rememberSaveable {mutableStateOf(false)}
    var range by rememberSaveable {mutableStateOf(false)}
    var showControls by rememberSaveable {mutableStateOf(false)}
    var saveZone by rememberSaveable {mutableStateOf(false)}
    var savePreset by rememberSaveable {mutableStateOf(false)}
    var pattern by rememberSaveable {mutableStateOf(false)}
    var copy by rememberSaveable {mutableStateOf(false)}
    var clipboard by remember {mutableStateOf<LightingPatch?>(null)}
    val grid=rememberLazyGridState()
    val order=remember(count,node.reverseDisplay) { (0 until count).toList().let {if(node.reverseDisplay) it.reversed() else it} }
    val observation=previewObservation(node,selected)
    val refs=selected.sorted().map {LedRef(node.uuid,it)}
    val draft=selected.singleOrNull()?.let {node.drafts[it]} ?: LightingPatch()
    LaunchedEffect(grid,node.uuid,node.reverseDisplay) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int } }.debounce(150).distinctUntilChanged().collect {onAction(UiAction.Visible(node.uuid,it))}
    }
    DisposableEffect(node.uuid) {onDispose {onAction(UiAction.CancelVisible)}}
    val controls: @Composable ()->Unit = {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            SectionLabel(stringResource(R.string.selected_count,selected.size))
            if(selected.isEmpty()) Text(stringResource(R.string.selection_required))
            else LightingControls(observation,draft=draft,capabilities=node.capabilities,favoriteColors=state.installation?.favoriteColors.orEmpty(),recentColors=state.installation?.recentColors.orEmpty(),enabled=!state.readOnly,
                onFavorite={onAction(UiAction.FavoriteColor(it))}) {patch,final -> onAction(UiAction.EditLight(node.uuid,selected,patch,final))}
            Text(stringResource(R.string.all_edit_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(node.drafts.isNotEmpty()) {
                Button({onAction(UiAction.ApplyDrafts(node.uuid))},enabled=state.connection==ConnectionStatus.CONNECTED&&!state.readOnly) {Text(stringResource(R.string.apply_changes))}
                TextButton({onAction(UiAction.DiscardDrafts(node.uuid))}) {Text(stringResource(R.string.discard))}
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
    Column(modifier.fillMaxSize()) {
        DetailTopBar(stringResource(R.string.led_editor),onBack=onBack) {ActionIcon(Glyph.REFRESH,R.string.read_all,{onAction(UiAction.RefreshStrip(node.uuid))},enabled=!state.readOnly)}
        Text(node.name,Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            TextButton({multiSelect=!multiSelect}) {Text(stringResource(if(multiSelect) R.string.done else R.string.select_leds))}
            TextButton({selected=(0 until count).toSet();multiSelect=true}) {Text(stringResource(R.string.select_all))}
            TextButton({selected=emptySet()}) {Text(stringResource(R.string.select_none))}
            TextButton({range=true}) {Text(stringResource(R.string.range))}
            TextButton({onAction(UiAction.Reverse(node.uuid))}) {Text(stringResource(R.string.reverse_order))}
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            OutlinedButton({copy=true},enabled=selected.size==1&&observation!=null) {Text(stringResource(R.string.copy_properties))}
            if(clipboard!=null) OutlinedButton({clipboard?.let {onAction(UiAction.EditLight(node.uuid,selected,it))}},enabled=selected.isNotEmpty()&&!state.readOnly) {Text(stringResource(R.string.paste_properties))}
            OutlinedButton({saveZone=true},enabled=selected.isNotEmpty()&&!state.readOnly) {Text(stringResource(R.string.save_as_zone))}
            OutlinedButton({savePreset=true},enabled=selected.isNotEmpty()&&!state.readOnly) {Text(stringResource(R.string.save_as_preset))}
            OutlinedButton({pattern=true},enabled=selected.isNotEmpty()&&!state.readOnly) {Text(stringResource(R.string.create_pattern))}
        }
        Text(stringResource(R.string.strip_help),Modifier.padding(horizontal=24.dp,vertical=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide=maxWidth>=840.dp
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    LazyVerticalGrid(GridCells.Adaptive(56.dp),Modifier.weight(1f),state=grid,contentPadding=PaddingValues(20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(order,key={it}) { index ->
                            val observed=previewObservation(node,setOf(index))
                            val known=observed!=null
                            val rgb=node.drafts[index]?.rgb ?: observed?.state?.rgb
                            val label=stringResource(R.string.led_cell_state,index+1,if(!known) stringResource(R.string.unknown) else if(observed.state.power) stringResource(R.string.on) else stringResource(R.string.off))
                            val isSelected=index in selected
                            val surface=MaterialTheme.colorScheme.surfaceContainerHigh
                            val cellColor=rgb?.composeColor()?.copy(alpha=if(observed?.state?.power==false) .20f else .65f)?.compositeOver(surface) ?: surface
                            val textColor=if(cellColor.luminance()>.35f) Color(0xFF17211C) else Color.White
                            val border=if(isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            Box(Modifier.aspectRatio(1f).sizeIn(minWidth=48.dp,minHeight=48.dp).clip(MaterialTheme.shapes.medium)
                                .background(cellColor)
                                .border(if(isSelected) 3.dp else 1.dp,border,MaterialTheme.shapes.medium)
                                .combinedClickable(onClick={selected=if(!multiSelect) setOf(index) else if(index in selected) selected-index else selected+index},
                                    onLongClick={multiSelect=true;selected=if(index in selected) selected-index else selected+index})
                                .semantics {contentDescription=label;this.selected=isSelected},contentAlignment=Alignment.Center) {
                                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                    Text((index+1).toString(),fontWeight=if(isSelected) FontWeight.Bold else FontWeight.Normal,color=textColor)
                                    if(!known) Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant,CircleShape))
                                }
                            }
                        }
                    }
                    if(!wide) Button({showControls=true},Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),enabled=selected.isNotEmpty()) {Text(stringResource(R.string.edit_selection));Spacer(Modifier.width(8.dp));Text(stringResource(R.string.selected_count,selected.size))}
                }
                if(wide) Surface(Modifier.width(360.dp).fillMaxHeight(),color=MaterialTheme.colorScheme.surfaceContainerLow) {controls()}
            }
        }
    }
    if(showControls) ModalBottomSheet(onDismissRequest={showControls=false}) {Box(Modifier.fillMaxWidth().heightIn(max=650.dp)) {controls()}}
    if(range) LedRangeDialog(count,{range=false}) {start,end -> selected=(start..end).toSet();multiSelect=true;range=false}
    if(saveZone) NameDialog(stringResource(R.string.create_zone),stringResource(R.string.zone_name),stringResource(R.string.default_zone),{saveZone=false}) {onAction(UiAction.SaveZone(it,refs));saveZone=false}
    if(savePreset) NameDialog(stringResource(R.string.capture_look),stringResource(R.string.preset_name),stringResource(R.string.untitled_preset),{savePreset=false}) {onAction(UiAction.CapturePreset(it,refs));savePreset=false}
    if(pattern) PatternDialog(refs,{pattern=false},onAction)
    if(copy && observation!=null) CopyPropertiesDialog(observation,{copy=false}) {clipboard=it;copy=false}
}
