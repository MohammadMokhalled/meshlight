package com.example.meshlightcontroller.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.meshlightcontroller.R
import com.example.meshlightcontroller.core.model.*
import java.util.Locale

@Composable
fun WelcomeScreen(modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onImport: ()->Unit) {
    var create by rememberSaveable { mutableStateOf(false) }
    val defaultHome=stringResource(R.string.default_home)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compactHeight = maxHeight < 650.dp
        Column(Modifier.widthIn(max=520.dp).fillMaxHeight().align(Alignment.Center).verticalScroll(rememberScrollState()).padding(horizontal=32.dp,vertical=24.dp),verticalArrangement=Arrangement.Center) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MeshIcon(Glyph.LIGHT,tint=MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.app_name),style=MaterialTheme.typography.titleLarge)
            }
            LightOrb(Rgb(255,192,117),Modifier.fillMaxWidth().height(if(compactHeight) 148.dp else 230.dp))
            Text(stringResource(R.string.welcome_title),style=MaterialTheme.typography.displayMedium)
            Text(stringResource(R.string.welcome_body),Modifier.padding(top=16.dp,bottom=32.dp),style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button({create=true},Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text(stringResource(R.string.create_home)) }
            OutlinedButton(onImport,Modifier.fillMaxWidth().padding(top=12.dp).heightIn(min=52.dp)) { Text(stringResource(R.string.import_network)) }
            TextButton({onAction(UiAction.EnterDemo)},Modifier.align(Alignment.CenterHorizontally).padding(top=8.dp)) { Text(stringResource(R.string.explore_demo)) }
            Text(stringResource(R.string.local_first),Modifier.align(Alignment.CenterHorizontally).padding(top=24.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(create) NameDialog(stringResource(R.string.create_home),stringResource(R.string.home_name),defaultHome,{create=false}) { onAction(UiAction.CreateNetwork(it));create=false }
}

@Composable
fun LightsScreen(state: ControllerState, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onNavigate: (Route)->Unit) {
    val nodes=state.installation?.nodes.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    val visible=nodes.filter { (!favorites || it.favorite) && it.name.contains(query,ignoreCase=true) }
    val cardWidth = if (LocalDensity.current.fontScale > 1.3f) 200.dp else 148.dp
    val addLabel = stringResource(R.string.add_light)
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(columns=GridCells.Adaptive(cardWidth),contentPadding=PaddingValues(start=24.dp,end=24.dp,top=16.dp,bottom=104.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item(span={GridItemSpan(maxLineSpan)}) { PageHeading(stringResource(R.string.your_lights),subtitle=stringResource(R.string.home_subtitle)) }
            if(nodes.isNotEmpty()) {
                item(span={GridItemSpan(maxLineSpan)}) {
                    OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text(stringResource(R.string.search_lights))},leadingIcon={MeshIcon(Glyph.SEARCH)},trailingIcon={if(query.isNotEmpty()) ActionIcon(Glyph.CLOSE,R.string.clear_search,{query=""})},singleLine=true,shape=MaterialTheme.shapes.large)
                }
                item(span={GridItemSpan(maxLineSpan)}) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(!favorites,{favorites=false},label={Text(stringResource(R.string.all_lights))})
                        FilterChip(favorites,{favorites=true},label={Text(stringResource(R.string.favorites))},leadingIcon={MeshIcon(Glyph.HEART,Modifier.size(16.dp))})
                    }
                }
            }
            if(visible.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {
                EmptyState(stringResource(if(nodes.isEmpty()) R.string.no_lights_title else R.string.no_results),stringResource(if(nodes.isEmpty()) R.string.no_lights_body else R.string.no_results_body))
            }
            items(visible,key={it.uuid}) { node -> LightCard(node,state.readOnly,online=state.connection==ConnectionStatus.CONNECTED,onOpen={onNavigate(Route("light",node.uuid))},onPower={onAction(UiAction.EditLight(node.uuid,setOf(LedTarget.All),LightingPatch(power=it)))}) }
        }
        if(!state.readOnly) ExtendedFloatingActionButton(onClick={onNavigate(Route("add"))},modifier=Modifier.align(Alignment.BottomEnd).padding(24.dp).semantics {contentDescription=addLabel},icon={MeshIcon(Glyph.ADD)},text={Text(addLabel)},containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun LightCard(node: NodeInfo, readOnly: Boolean, modifier: Modifier = Modifier, online: Boolean = true, onOpen: ()->Unit, onPower: (Boolean)->Unit) {
    val value=previewObservation(node,setOf(LedTarget.All))
    val rgb=node.drafts[LedTarget.All]?.rgb ?: value?.state?.rgb
    val color=rgb?.composeColor() ?: MaterialTheme.colorScheme.outline
    val power=node.drafts[LedTarget.All]?.power ?: value?.state?.power
    val openLabel=stringResource(R.string.open_light,node.name)
    Surface(onClick=onOpen,modifier=modifier.fillMaxWidth().semantics {contentDescription=openLabel},shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.background(Brush.verticalGradient(listOf(color.copy(alpha=.11f),Color.Transparent))).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
                LightOrb(rgb,Modifier.size(64.dp),power)
                Spacer(Modifier.weight(1f))
                if(node.stage==SetupStage.READY && node.capabilities?.supportsPower!=false) {
                    FilledIconButton(onClick={onPower(power!=true || value?.mixedPower==true)},enabled=!readOnly,modifier=Modifier.size(48.dp),
                        colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(power==true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,contentColor=if(power==true) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                        MeshIcon(Glyph.POWER,description=stringResource(if(power==true) R.string.turn_light_off else R.string.turn_light_on,node.name))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(node.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(node.favorite) MeshIcon(Glyph.HEART,Modifier.padding(start=4.dp).size(14.dp),MaterialTheme.colorScheme.primary)
            }
            Text(if(node.stage!=SetupStage.READY) stringResource(R.string.setup_needed)
                else if(value==null) stringResource(R.string.unknown)
                else if(value.mixedPower || value.mixedIntensity) stringResource(R.string.mixed)
                else if(!value.state.power) stringResource(R.string.off)
                else stringResource(R.string.brightness_value,value.state.intensity),
                Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if(node.drafts.isNotEmpty()) stringResource(R.string.preview) else if(!online || value?.let {it.receivedAt==0L || System.currentTimeMillis()-it.receivedAt>30000}==true) stringResource(R.string.last_known) else node.capabilities?.ledCount?.let { stringResource(R.string.led_count,it) } ?: "",
                Modifier.padding(top=4.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DetailTopBar(title: String, modifier: Modifier = Modifier, onBack: ()->Unit, actions: @Composable RowScope.()->Unit = {}) {
    Row(modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        ActionIcon(Glyph.BACK,R.string.back,onBack)
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun LightDetailScreen(node: NodeInfo, state: ControllerState, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onNavigate: (Route)->Unit, onBack: ()->Unit) {
    var rename by rememberSaveable(node.uuid) { mutableStateOf(false) }
    var fade by rememberSaveable(node.uuid) { mutableLongStateOf(0) }
    val observation=previewObservation(node,setOf(LedTarget.All))
    val draft=node.drafts[LedTarget.All] ?: LightingPatch()
    LaunchedEffect(node.uuid) { if(node.stage==SetupStage.READY && state.connection==ConnectionStatus.CONNECTED) onAction(UiAction.Refresh(node.uuid)) }
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { DetailTopBar(node.name,onBack=onBack) {
            if(node.capabilities?.supportsIndexed==true) ActionIcon(Glyph.LEDS,R.string.open_led_editor,{onNavigate(Route("strip",node.uuid))})
            ActionIcon(Glyph.HEART,if(node.favorite) R.string.unfavorite else R.string.favorite,{onAction(UiAction.Favorite(node.uuid))},selected=node.favorite)
            ActionIcon(Glyph.EDIT,R.string.rename,{rename=true},enabled=!state.readOnly)
        } }
        if(node.stage!=SetupStage.READY) item {
            InfoCard(stringResource(if(node.stage==SetupStage.UNSUPPORTED) R.string.unsupported else R.string.setup_help),Modifier.padding(horizontal=24.dp),error=node.stage==SetupStage.UNSUPPORTED) {
                Button({onAction(UiAction.FinishSetup(node.uuid))},enabled=!state.readOnly && state.operation?.running!=true) { Text(stringResource(R.string.finish_setup)) }
            }
        }
        item { LightOrb(draft.rgb ?: observation?.state?.rgb,Modifier.fillMaxWidth().height(148.dp),draft.power ?: observation?.state?.power) }
        if(node.stage==SetupStage.READY) item(key="controls") {
            LightingControls(observation,Modifier.padding(horizontal=24.dp),draft,node.capabilities,state.installation?.favoriteColors.orEmpty(),state.installation?.recentColors.orEmpty(),enabled=!state.readOnly,
                onFavorite={onAction(UiAction.FavoriteColor(it))}) { patch,final ->
                if(final || fade==0L || patch.rgb!=null) onAction(UiAction.EditLight(node.uuid,setOf(LedTarget.All),patch,final,if(final && patch.rgb==null) fade else 0))
            }
        }
        node.transition?.let {transition -> item {TransitionNotice(transition,Modifier.padding(horizontal=24.dp))}}
        if(state.connection!=ConnectionStatus.CONNECTED && !state.readOnly) item { InfoCard(stringResource(R.string.offline_preview),Modifier.padding(horizontal=24.dp)) { TextButton({onAction(UiAction.Connect)}) {Text(stringResource(R.string.connect))} } }
        if(node.drafts.keys.any { "${node.uuid}/$it" !in state.pendingTargets }) item {
            InfoCard(stringResource(R.string.draft_pending),Modifier.padding(horizontal=24.dp)) {
                Row {
                    Button({onAction(UiAction.ApplyDrafts(node.uuid))},enabled=!state.readOnly && state.connection==ConnectionStatus.CONNECTED) {Text(stringResource(R.string.apply_changes))}
                    TextButton({onAction(UiAction.DiscardDrafts(node.uuid))}) {Text(stringResource(R.string.discard))}
                }
            }
        }
        if(node.capabilities?.supportsIndexed==true) item {
            OutlinedButton({onNavigate(Route("strip",node.uuid))},Modifier.fillMaxWidth().padding(horizontal=24.dp).heightIn(min=56.dp)) {
                MeshIcon(Glyph.LEDS);Spacer(Modifier.width(12.dp));Text(stringResource(R.string.edit_leds));Spacer(Modifier.weight(1f));MeshIcon(Glyph.ARROW,Modifier.size(18.dp))
            }
        }
        item {
            Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.fade))
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(0L,500L,1000L,2000L,5000L).forEach { millis ->
                        FilterChip(selected=fade==millis,onClick={fade=millis},label={Text(if(millis==0L) stringResource(R.string.immediate) else stringResource(R.string.fade_seconds,if(millis==500L) "0.5" else (millis/1000).toString()))})
                    }
                }
                Text(stringResource(R.string.fade_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical=12.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton({onAction(UiAction.Identify(node.uuid))},enabled=!state.readOnly) {MeshIcon(Glyph.SEARCH,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(stringResource(R.string.identify))}
                    TextButton({onAction(UiAction.Refresh(node.uuid))}) {Text(stringResource(R.string.refresh))}
                }
                Text(stringResource(R.string.identify_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({onNavigate(Route("device",node.uuid))}) {Text(stringResource(R.string.device_info));MeshIcon(Glyph.ARROW,Modifier.size(18.dp))}
                Text(stringResource(R.string.all_edit_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if(state.connection!=ConnectionStatus.CONNECTED || observation?.let {it.receivedAt==0L || System.currentTimeMillis()-it.receivedAt>30000}==true) R.string.state_stale else R.string.state_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if(rename) NameDialog(stringResource(R.string.rename),stringResource(R.string.light_name),node.name,{rename=false}) {onAction(UiAction.RenameLight(node.uuid,it));rename=false}
}

@Composable
fun DiscoveryScreen(state: ControllerState, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onBack: ()->Unit) {
    var selected by remember { mutableStateOf<NearbyLight?>(null) }
    var replacement by remember { mutableStateOf<Pair<NearbyLight,String>?>(null) }
    val defaultName=stringResource(R.string.default_light)
    DisposableEffect(Unit) { onDispose {onAction(UiAction.StopScan)} }
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {DetailTopBar(stringResource(R.string.add_light),onBack=onBack)}
        if(state.demo) item {InfoCard(stringResource(R.string.demo_no_provision),Modifier.padding(horizontal=24.dp)) {TextButton({onAction(UiAction.LeaveDemo)}) {Text(stringResource(R.string.leave_demo))}}}
        item {PageHeading(stringResource(R.string.nearby_title),Modifier.padding(horizontal=24.dp),stringResource(R.string.nearby_body))}
        item {
            Button({onAction(if(state.connection==ConnectionStatus.SCANNING) UiAction.StopScan else UiAction.Scan)},Modifier.padding(horizontal=24.dp),enabled=state.operation?.running!=true&&!state.demo) {
                MeshIcon(if(state.connection==ConnectionStatus.SCANNING) Glyph.CLOSE else Glyph.BLUETOOTH,Modifier.size(20.dp));Spacer(Modifier.width(8.dp))
                Text(stringResource(if(state.connection==ConnectionStatus.SCANNING) R.string.stop_scan else R.string.start_scan))
            }
        }
        if(state.connection==ConnectionStatus.SCANNING) item {LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=24.dp))}
        if(state.nearby.isEmpty()) item {EmptyState(stringResource(R.string.no_nearby),stringResource(R.string.no_nearby_body),glyph=Glyph.BLUETOOTH)}
        items(state.nearby,key={it.uuid}) { light ->
            Surface(onClick={selected=light},enabled=state.operation?.running!=true,modifier=Modifier.padding(horizontal=24.dp).fillMaxWidth(),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                    MeshIcon(Glyph.LIGHT,tint=MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal=16.dp)) {Text(light.name ?: defaultName,style=MaterialTheme.typography.titleMedium);Text(stringResource(R.string.nearby_signal,light.rssi),style=MaterialTheme.typography.bodySmall)}
                    MeshIcon(Glyph.ADD)
                }
            }
        }
        item {Text(stringResource(R.string.adding_help),Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {InfoCard(stringResource(R.string.physical_reset),Modifier.padding(horizontal=24.dp))}
    }
    selected?.let { light ->
        NameDialog(stringResource(R.string.add_light),stringResource(R.string.light_name),light.name ?: defaultName,{selected=null}) { name ->
            selected=null
            if(state.installation?.nodes?.any {it.uuid.equals(light.uuid,true)}==true) replacement=light to name
            else onAction(UiAction.AddLight(light,name))
        }
    }
    replacement?.let { (light,name) ->
        ConfirmDialog(stringResource(R.string.replace_light_title),stringResource(R.string.replace_light_body),stringResource(R.string.provision),{replacement=null}) { onAction(UiAction.AddLight(light,name,true));replacement=null }
    }
}
