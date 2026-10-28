package com.example.meshlightcontroller.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.meshlightcontroller.core.mesh.DemoMeshGateway
import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.ui.theme.MeshLightControllerTheme

@Composable
fun MeshPreview(content: @Composable () -> Unit) {
    // The standalone screenshot renderer has no ComponentActivity to own back navigation.
    val owner = remember { object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = NavigationEventDispatcher()
    } }
    DisposableEffect(owner) { onDispose { owner.navigationEventDispatcher.dispose() } }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
        MeshLightControllerTheme(content = content)
    }
}

fun sampleHome(): ControllerState = ControllerState(DemoMeshGateway().installation(),ConnectionStatus.CONNECTED,demo=true)

@Preview(name="Phone · light",widthDp=390,heightDp=844,showBackground=true)
@Preview(name="Phone · dark",widthDp=390,heightDp=844,showBackground=true,uiMode=Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Tablet · light",widthDp=1280,heightDp=800,showBackground=true)
@Preview(name="Large type",widthDp=360,heightDp=800,fontScale=1.5f,showBackground=true)
@Composable
fun HomePreview() {MeshPreview {MeshLightScreen(sampleHome())}}

@Preview(name="Welcome",widthDp=390,heightDp=844,showBackground=true)
@Composable
fun WelcomePreview() {MeshPreview {MeshLightScreen(ControllerState())}}

@Preview(name="Light controls",widthDp=390,heightDp=844,showBackground=true)
@Preview(name="Light controls · dark",widthDp=390,heightDp=844,showBackground=true,uiMode=Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LightControlsPreview() {
    val state=sampleHome()
    MeshLightControllerTheme {Surface(Modifier.safeDrawingPadding()) {LightDetailScreen(state.installation!!.nodes.first(),state,Modifier.fillMaxSize(),{},{},{})}}
}

@Preview(name="LED editor",widthDp=390,heightDp=844,showBackground=true)
@Preview(name="LED editor · tablet",widthDp=1280,heightDp=800,showBackground=true)
@Composable
fun LedEditorPreview() {
    val state=sampleHome()
    MeshLightControllerTheme {Surface(Modifier.safeDrawingPadding()) {StripScreen(state.installation!!.nodes[2],state,Modifier.fillMaxSize(),{},{})}}
}

@Preview(name="Rooms",widthDp=390,heightDp=844,showBackground=true)
@Composable
fun RoomsPreview() {MeshLightControllerTheme {Surface(Modifier.safeDrawingPadding()) {GroupsScreen(sampleHome(),Modifier.fillMaxSize(),{})}}}

@Preview(name="Presets",widthDp=390,heightDp=844,showBackground=true)
@Composable
fun PresetsPreview() {MeshLightControllerTheme {Surface(Modifier.safeDrawingPadding()) {PresetsScreen(sampleHome(),Modifier.fillMaxSize(),{},{})}}}
