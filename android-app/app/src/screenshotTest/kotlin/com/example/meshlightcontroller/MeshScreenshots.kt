package com.example.meshlightcontroller

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.example.meshlightcontroller.core.mesh.NodeSettings
import com.example.meshlightcontroller.data.UiPreferences
import com.example.meshlightcontroller.ui.*

@Preview(name="Compact short",widthDp=400,heightDp=400)
@Preview(name="Compact medium",widthDp=400,heightDp=500)
@Preview(name="Compact tall",widthDp=400,heightDp=1000)
@Preview(name="Medium short",widthDp=610,heightDp=400)
@Preview(name="Medium medium",widthDp=610,heightDp=500)
@Preview(name="Medium tall",widthDp=610,heightDp=1000)
@Preview(name="Expanded short",widthDp=900,heightDp=400)
@Preview(name="Expanded medium",widthDp=900,heightDp=500)
@Preview(name="Expanded tall",widthDp=900,heightDp=1000)
@Preview(name="Dark",widthDp=400,heightDp=500,uiMode=Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Large text",widthDp=400,heightDp=500,fontScale=1.5f)
annotation class ScreenSizes

class MeshScreenshots {
    @PreviewTest @ScreenSizes @Composable
    fun home() {MeshPreview {MeshLightScreen(sampleHome())}}

    @PreviewTest @ScreenSizes @Composable
    fun welcome() {WelcomePreview()}

    @PreviewTest @ScreenSizes @Composable
    fun controls() {LightControlsPreview()}

    @PreviewTest @ScreenSizes @Composable
    fun strip() {LedEditorPreview()}

    @PreviewTest @ScreenSizes @Composable
    fun groups() {RoomsPreview()}

    @PreviewTest @ScreenSizes @Composable
    fun presets() {PresetsPreview()}

    @PreviewTest @ScreenSizes @Composable
    fun room() {
        val state=sampleHome()
        MeshPreview {Surface {RoomDetailScreen(state.installation!!.rooms.first(),state,Modifier.fillMaxSize(),{},{},{})}}
    }

    @PreviewTest @ScreenSizes @Composable
    fun zone() {
        val state=sampleHome()
        MeshPreview {Surface {ZoneDetailScreen(state.installation!!.zones.first(),state,Modifier.fillMaxSize(),{},{},{})}}
    }

    @PreviewTest @ScreenSizes @Composable
    fun settings() {
        MeshPreview {Surface {SettingsScreen(sampleHome(),UiPreferences(),Modifier.fillMaxSize(),{},{},{},{})}}
    }

    @PreviewTest @ScreenSizes @Composable
    fun roomEditor() {
        MeshPreview {Surface {RoomEditorScreen(null,sampleHome(),Modifier.fillMaxSize(),{},{})}}
    }

    @PreviewTest @ScreenSizes @Composable
    fun zoneEditor() {
        MeshPreview {Surface {ZoneEditorScreen(null,sampleHome(),Modifier.fillMaxSize(),{},{})}}
    }

    @PreviewTest @ScreenSizes @Composable
    fun device() {
        val state=sampleHome()
        MeshPreview {Surface {DeviceScreen(state.installation!!.nodes.first(),state,NodeSettings(7,true,true,true),Modifier.fillMaxSize(),{},{})}}
    }
}
