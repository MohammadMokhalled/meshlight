package com.example.meshlightcontroller.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.meshlightcontroller.R
import com.example.meshlightcontroller.core.mesh.NodeSettings
import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.data.UiPreferences

data class Route(val type: String, val id: String = "") : NavKey

@Composable
private fun rememberTabStack(networkId: String, root: String): SnapshotStateList<Route> =
    rememberSaveable(networkId,root,saver=listSaver(
        save={stack: SnapshotStateList<Route> -> stack.map {it.type+":"+it.id}},
        restore={saved -> saved.map {val split=it.split(':',limit=2);Route(split[0],split.getOrElse(1){""})}.toMutableStateList()}
    )) {mutableStateListOf(Route(root))}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MeshLightScreen(
    state: ControllerState,
    modifier: Modifier = Modifier,
    preferences: UiPreferences = UiPreferences(),
    settings: Pair<String,NodeSettings>? = null,
    onAction: (UiAction)->Unit = {},
    onExport: (ExportType)->Unit = {},
    onImport: ()->Unit = {}
) {
    val networkId=state.installation?.networkId.orEmpty()
    var tab by rememberSaveable(networkId) {mutableIntStateOf(0)}
    val lightStack=rememberTabStack(networkId,"lights")
    val groupStack=rememberTabStack(networkId,"groups")
    val presetStack=rememberTabStack(networkId,"presets")
    val stack=when(tab){0->lightStack;1->groupStack;else->presetStack}
    val navigate: (Route)->Unit = {route ->
        if(stack.lastOrNull()!=route) {
            // Replacing a detail keeps a single list/detail pair on expanded windows.
            if(route.type in setOf("light","room","zone","preset") && stack.lastOrNull()?.type in setOf("light","room","zone","preset")) stack.removeLastOrNull()
            stack.add(route)
        }
    }
    val back: ()->Unit = {if(stack.size>1) stack.removeLastOrNull()}
    val strategy=rememberListDetailSceneStrategy<Route>()
    val roots=listOf(R.string.lights,R.string.groups,R.string.presets)
    val glyphs=listOf(Glyph.LIGHT,Glyph.GROUP,Glyph.PRESET)
    // NavEntry instances are cached by route. Read current inputs inside their content.
    val latestState by rememberUpdatedState(state)
    val latestPreferences by rememberUpdatedState(preferences)
    val latestSettings by rememberUpdatedState(settings)
    val latestAction by rememberUpdatedState(onAction)
    val latestExport by rememberUpdatedState(onExport)
    val latestImport by rememberUpdatedState(onImport)
    val latestNavigate by rememberUpdatedState(navigate)
    val latestBack by rememberUpdatedState(back)
    val snackbar=remember(networkId) {SnackbarHostState()}
    LaunchedEffect(snackbar,state.error) {
        val error=state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(error,withDismissAction=true,duration=SnackbarDuration.Long)
        latestAction(UiAction.DismissError)
    }
    val entryProvider: (Route)->NavEntry<Route> = {route ->
        val metadata=when(route.type) {
            "lights" -> ListDetailSceneStrategy.listPane(detailPlaceholder={EmptyState(stringResource(R.string.choose_light),"",Modifier.fillMaxHeight(),Glyph.LIGHT)})
            "groups" -> ListDetailSceneStrategy.listPane(detailPlaceholder={EmptyState(stringResource(R.string.choose_group),"",Modifier.fillMaxHeight(),Glyph.GROUP)})
            "presets" -> ListDetailSceneStrategy.listPane(detailPlaceholder={EmptyState(stringResource(R.string.choose_preset),"",Modifier.fillMaxHeight(),Glyph.PRESET)})
            "light","room","zone","preset" -> ListDetailSceneStrategy.detailPane()
            else -> emptyMap()
        }
        NavEntry(route,metadata=metadata) {
            val state=latestState
            val preferences=latestPreferences
            val settings=latestSettings
            val onAction=latestAction
            val onExport=latestExport
            val onImport=latestImport
            val navigate=latestNavigate
            val back=latestBack
            val installation=state.installation ?: return@NavEntry
            val node=installation.nodes.firstOrNull {it.uuid==route.id}
            when(route.type) {
                "lights" -> LightsScreen(state,onAction=onAction,onNavigate=navigate)
                "groups" -> GroupsScreen(state,onNavigate=navigate)
                "presets" -> PresetsScreen(state,onAction=onAction,onNavigate=navigate)
                "light" -> if(node!=null) LightDetailScreen(node,state,onAction=onAction,onNavigate=navigate,onBack=back) else MissingItem(back)
                "strip" -> if(node!=null) StripScreen(node,state,onAction=onAction,onBack=back) else MissingItem(back)
                "add" -> DiscoveryScreen(state,onAction=onAction,onBack=back)
                "room" -> installation.rooms.firstOrNull {it.address.toString()==route.id}?.let {RoomDetailScreen(it,state,onAction=onAction,onNavigate=navigate,onBack=back)} ?: MissingItem(back)
                "zone" -> installation.zones.firstOrNull {it.id==route.id}?.let {ZoneDetailScreen(it,state,onAction=onAction,onNavigate=navigate,onBack=back)} ?: MissingItem(back)
                "preset" -> installation.presets.firstOrNull {it.id==route.id}?.let {PresetDetailScreen(it,state,onAction=onAction,onBack=back)} ?: MissingItem(back)
                "roomEdit" -> RoomEditorScreen(installation.rooms.firstOrNull {it.address.toString()==route.id},state,onAction=onAction,onBack=back)
                "zoneEdit" -> ZoneEditorScreen(installation.zones.firstOrNull {it.id==route.id},state,onAction=onAction,onBack=back)
                "settings" -> SettingsScreen(state,preferences,onAction=onAction,onBack=back,onExport=onExport,onImport=onImport)
                "device" -> if(node!=null) DeviceScreen(node,state,settings?.takeIf {it.first==node.uuid}?.second,onAction=onAction,onBack=back) else MissingItem(back)
            }
        }
    }
    val entries=key(networkId) {
        // Keep decorators alive for all tabs, including each tab's scroll and editor state.
        val lights=rememberDecoratedNavEntries(lightStack,listOf(rememberSaveableStateHolderNavEntryDecorator()),entryProvider)
        val groups=rememberDecoratedNavEntries(groupStack,listOf(rememberSaveableStateHolderNavEntryDecorator()),entryProvider)
        val presets=rememberDecoratedNavEntries(presetStack,listOf(rememberSaveableStateHolderNavEntryDecorator()),entryProvider)
        when(tab) {0->lights;1->groups;else->presets}
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide=maxWidth>=600.dp
        Scaffold(
            containerColor=MaterialTheme.colorScheme.background,
            contentWindowInsets=WindowInsets.safeDrawing,
            topBar={
                if(state.installation!=null) Column(Modifier.statusBarsPadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                    Row(Modifier.fillMaxWidth().padding(start=24.dp,end=8.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.app_name),style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(state.installation.name,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        ConnectionBadge(state.connection,demo=state.demo,pendingCount=state.pendingTargets.size,
                            onCancel={onAction(UiAction.CancelOperation)},onConnect={onAction(UiAction.Connect)})
                        ActionIcon(Glyph.SETTINGS,R.string.settings,{navigate(Route("settings"))})
                    }
                    if(state.demo) Surface(color=MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=24.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(stringResource(R.string.demo_banner),Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                            TextButton({onAction(UiAction.LeaveDemo)}) {Text(stringResource(R.string.leave_demo))}
                        }
                    }
                }
            },
            snackbarHost={
                if(state.error!=null) SnackbarHost(snackbar)
                else state.operation?.let {OperationCard(it,Modifier.padding(horizontal=16.dp,vertical=8.dp),onAction)}
            },
            bottomBar={
                if(!wide&&state.installation!=null) NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
                    roots.forEachIndexed {index,label->NavigationBarItem(selected=tab==index,onClick={tab=index},icon={MeshIcon(glyphs[index])},label={Text(stringResource(label))})}
                }
            }
        ) {padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if(state.readOnly) InfoCard(stringResource(R.string.transferred_body),Modifier.padding(horizontal=16.dp,vertical=4.dp),error=true)
                when {
                    state.loading -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {CircularProgressIndicator()}
                    state.installation==null -> WelcomeScreen(Modifier.weight(1f),onAction,onImport)
                    else -> Row(Modifier.weight(1f)) {
                        if(wide) NavigationRail(containerColor=MaterialTheme.colorScheme.background) {
                            Spacer(Modifier.height(16.dp))
                            roots.forEachIndexed {index,label->NavigationRailItem(selected=tab==index,onClick={tab=index},icon={MeshIcon(glyphs[index])},label={Text(stringResource(label))},modifier=Modifier.padding(vertical=8.dp))}
                        }
                        NavDisplay(
                            entries=entries,
                            modifier=Modifier.weight(1f).fillMaxHeight(),
                            onBack=back,
                            sceneStrategies=listOf(strategy)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingItem(onBack: ()->Unit) {
    EmptyState(stringResource(R.string.not_available),"",action={TextButton(onBack) {Text(stringResource(R.string.back))}})
}
